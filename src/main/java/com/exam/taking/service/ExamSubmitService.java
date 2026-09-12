package com.exam.taking.service;

import com.exam.anticheat.collector.BehaviorEventTypes;
import com.exam.anticheat.service.BehaviorEventCollectService;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.auth.security.SecurityUtil;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.entity.ExamSubmitDedup;
import com.exam.submission.mapper.ExamSubmitDedupMapper;
import com.exam.submission.mq.ExamSubmitSender;
import com.exam.submission.service.ExamSubmissionService;
import com.exam.taking.dto.SubmitRequest;
import com.exam.taking.dto.SubmitResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 交卷服务（spec「交卷幂等」「超时交卷」需求，三路竞态的唯一入口）：
 *
 * <p>可靠性设计（docs/需求决策记录.md §1.1-§2.3）：
 * <ol>
 *   <li>幂等快速路径：已交卷直接返回首次结果（重复点击/重复请求零成本）；</li>
 *   <li>第一重 SETNX 一次性锁：三路竞态（手动/前端归零/后端兜底）在此收敛为单飞串行；</li>
 *   <li>第二重防重表先查后插：uk_submit_dedup 唯一索引兜底并发插入；</li>
 *   <li>第三重状态机 CAS：进行中→已交卷仅一次，唯一赢家才继续；</li>
 *   <li>MQ 削峰：赢家发交卷消息（confirm 等待），答案由消费者批量落库；
 *       发送失败则答案暂存草稿，由对账补发扫描器重新投递（自愈，见 ExamSweepService）。</li>
 * </ol>
 *
 * <p>主事务只做状态 CAS 等核心字段（大答案 JSON 不进同步事务，避免长事务拖慢峰值），
 * 答案异步批量落库——5000 人同时交卷的可靠性来自上述层层收敛 + 最终一致。
 */
@Slf4j
@Service
public class ExamSubmitService {

    /** SETNX 一次性锁 key 前缀：exam:submit:lock:{examId}:{studentId} */
    static final String LOCK_PREFIX = "exam:submit:lock:";

    /** 锁竞争等待轮询参数：并发路径占锁时短轮询等其完成，等不到按冲突处理 */
    private static final int WAIT_ROUNDS = 20;
    private static final long WAIT_INTERVAL_MS = 100;

    private final ExamSubmissionService submissionService;
    private final ExamSubmitDedupMapper dedupMapper;
    private final ExamDraftService draftService;
    private final ExamSubmitSender sender;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final BehaviorEventCollectService eventCollectService;

    @Value("${exam.taking.submit.lock-ttl-seconds:30}")
    private int lockTtlSeconds;

    public ExamSubmitService(ExamSubmissionService submissionService, ExamSubmitDedupMapper dedupMapper,
                             ExamDraftService draftService, ExamSubmitSender sender,
                             StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                             BehaviorEventCollectService eventCollectService) {
        this.submissionService = submissionService;
        this.dedupMapper = dedupMapper;
        this.draftService = draftService;
        this.sender = sender;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.eventCollectService = eventCollectService;
    }

    /** 学生侧交卷入口：手动交卷与前端倒计时归零强制提交共用。 */
    public SubmitResponse submit(Long examId, SubmitRequest request) {
        Long studentId = requireStudent();
        SubmitRequest req = request == null ? new SubmitRequest() : request;
        int submitType = resolveSubmitType(req.getSubmitType());
        return doSubmit(examId, studentId, submitType, req.getAnswers());
    }

    /** 后端兜底入口（定时扫描/重进超时窗口调用）：无登录上下文，答案取 Redis 草稿。 */
    public SubmitResponse forceSubmitByBackend(Long examId, Long studentId) {
        return doSubmit(examId, studentId, ExamSubmission.SUBMIT_TYPE_BACKEND, null);
    }

    /** 三路竞态共用核心：幂等快速路径 → SETNX 锁 → 防重表 → 状态机 CAS → MQ 削峰。 */
    private SubmitResponse doSubmit(Long examId, Long studentId, int submitType, JsonNode payloadAnswers) {
        // 幂等快速路径：已交卷直接返回首次结果（spec「重复交卷幂等」场景），不抢锁不发消息
        ExamSubmission submission = submissionService.getByExamStudent(examId, studentId);
        if (submission == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "尚未进入考试，无法交卷");
        }
        if (submission.getStatus() == ExamSubmission.STATUS_SUBMITTED) {
            return toResponse(submission);
        }

        String answersJson = resolveAnswers(examId, studentId, payloadAnswers);

        // 第一重：SETNX 一次性锁（单飞）——三路竞态并发提交在此收敛为一个执行流
        String lockKey = LOCK_PREFIX + examId + ":" + studentId;
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, UUID.randomUUID().toString(), Duration.ofSeconds(lockTtlSeconds));
        if (!Boolean.TRUE.equals(locked)) {
            return waitForConcurrentSubmit(examId, studentId);
        }
        try {
            // 锁内二次确认：等锁窗口内可能已被并发路径提交
            submission = submissionService.getByExamStudent(examId, studentId);
            if (submission.getStatus() == ExamSubmission.STATUS_SUBMITTED) {
                return toResponse(submission);
            }

            // 第二重：防重表先查后插（uk_submit_dedup 唯一索引兜底并发插入）
            recordDedupQuietly(examId, studentId, submission.getId(), submitType);

            // 第三重：状态机 CAS（进行中→已交卷仅一次），0 行 = 并发赢家已存在
            LocalDateTime now = LocalDateTime.now();
            if (!submissionService.casSubmitToSubmitted(submission.getId(), now, submitType)) {
                ExamSubmission latest = submissionService.getByExamStudent(examId, studentId);
                if (latest != null && latest.getStatus() == ExamSubmission.STATUS_SUBMITTED) {
                    return toResponse(latest);
                }
                throw new BusinessException(ResponseCode.STATE_CONFLICT, "提交冲突，请稍后重试");
            }

            // MQ 削峰：答案异步批量落库（confirm 失败 → 答案暂存草稿，由对账补发扫描器自愈）
            try {
                sender.send(new com.exam.submission.dto.SubmitMessage(
                        submission.getId(), examId, studentId, submitType, now, answersJson));
            } catch (Exception e) {
                log.error("交卷消息发送失败，答案暂存草稿等待补发: submission={} exam={} student={}",
                        submission.getId(), examId, studentId, e);
                // 阶段 7 防作弊：交卷异常事件采集（策略判定严重度为"高"），旁路不改变原有兜底流程
                recordSubmitAnomaly(examId, studentId, e);
                draftService.overwriteAnswers(examId, studentId, answersJson);
                throw new BusinessException(ResponseCode.INTERNAL_ERROR,
                        "系统繁忙，答卷已锁定，答案将自动补交，请勿重复提交");
            }

            log.info("学生 {} 交卷成功: exam={} submission={} type={} 答案字节={}",
                    studentId, examId, submission.getId(), submitType, answersJson.length());
            return new SubmitResponse(submission.getId(), examId, studentId,
                    ExamSubmission.STATUS_SUBMITTED, now, submitType);
        } finally {
            // 主动释放锁（TTL 兜底防持有者崩溃后死锁）
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 交卷异常事件采集（spec add-anti-cheat「补齐交卷异常事件」）：经统一采集核心落库，
     * 严重度由 SUBMIT_ANOMALY 策略判定为"高"。事件采集是旁路——内部已消化异常，
     * 此处再兜一层，保证绝不影响交卷主链路的草稿兜底与异常抛出。
     */
    private void recordSubmitAnomaly(Long examId, Long studentId, Exception cause) {
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("stage", "MQ_SEND");
            data.put("reason", cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
            eventCollectService.collect(examId, studentId, BehaviorEventTypes.SUBMIT_ANOMALY, data, null);
        } catch (Exception e) {
            log.warn("交卷异常事件采集失败（旁路忽略）: exam={} student={}", examId, studentId, e);
        }
    }

    /**
     * 防重表先查后插：插入成功即占有首次提交身份；并发插入撞唯一索引
     * （DuplicateKeyException）说明已有提交在途，继续走状态机 CAS 裁决即可。
     * 先前尝试若在"插入防重后、CAS 前"崩溃，此处查到已有记录但答卷未交卷——继续 CAS 自愈。
     */
    private void recordDedupQuietly(Long examId, Long studentId, Long submissionId, int submitType) {
        if (dedupMapper.selectByExamStudent(examId, studentId) != null) {
            return;
        }
        try {
            ExamSubmitDedup dedup = new ExamSubmitDedup();
            dedup.setExamId(examId);
            dedup.setStudentId(studentId);
            dedup.setSubmissionId(submissionId);
            dedup.setSubmitType(submitType);
            dedupMapper.insert(dedup);
        } catch (DuplicateKeyException e) {
            log.info("防重表并发插入冲突（已有提交在途）: exam={} student={}", examId, studentId);
        }
    }

    /** 锁被并发路径占用：短轮询等其完成后幂等返回；等待超时按状态冲突处理。 */
    private SubmitResponse waitForConcurrentSubmit(Long examId, Long studentId) {
        for (int i = 0; i < WAIT_ROUNDS; i++) {
            try {
                Thread.sleep(WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            ExamSubmission latest = submissionService.getByExamStudent(examId, studentId);
            if (latest != null && latest.getStatus() == ExamSubmission.STATUS_SUBMITTED) {
                return toResponse(latest);
            }
        }
        throw new BusinessException(ResponseCode.STATE_CONFLICT, "正在提交中，请稍候重试");
    }

    /**
     * 答案来源优先级：请求体（手动/前端归零的最终状态）→ Redis 草稿（后端兜底/断连场景，
     * 最多滞后一个保存周期）→ 空答案。始终产出非空 JSON（"{}"），与"answers IS NULL = 未落库"
     * 的对账标记互不干扰。
     */
    private String resolveAnswers(Long examId, Long studentId, JsonNode payloadAnswers) {
        if (payloadAnswers != null && payloadAnswers.isObject()) {
            return payloadAnswers.toString();
        }
        ExamDraftService.DraftState draft = draftService.get(examId, studentId);
        if (draft != null && draft.answers() != null && draft.answers().isObject()) {
            return draft.answers().toString();
        }
        return "{}";
    }

    private int resolveSubmitType(String type) {
        if (SubmitRequest.TYPE_COUNTDOWN_ZERO.equals(type)) {
            return ExamSubmission.SUBMIT_TYPE_COUNTDOWN_ZERO;
        }
        return ExamSubmission.SUBMIT_TYPE_MANUAL;
    }

    private SubmitResponse toResponse(ExamSubmission submission) {
        return new SubmitResponse(submission.getId(), submission.getExamId(), submission.getStudentId(),
                submission.getStatus(), submission.getSubmitTime(), submission.getSubmitType());
    }

    private Long requireStudent() {
        Long userId = SecurityUtil.getUserId();
        if (userId == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return userId;
    }
}
