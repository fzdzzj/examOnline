package com.exam.taking.service;

import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.submission.mq.ExamSubmitSender;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 交卷链路兜底扫描（spec「超时交卷」需求，服务端时间为准）：
 *
 * <p>三路竞态中的"后端定时兜底"一路，同时承担 MQ 极端丢消息场景的对账自愈：
 * <ol>
 *   <li>超时强制交卷：扫描"进行中且（个人已超时 或 所属考试已结束/已批改）"的答卷
 *       （教师提前结束 force_end 后考试态为已结束，同样命中），经
 *       {@link ExamSubmitService#forceSubmitByBackend} 强制交卷——与手动/前端归零
 *       共享 SETNX 锁与状态机 CAS，三路并发也只提交一次；答案取 Redis 草稿
 *       （最后自动保存，最多滞后一个保存周期），无草稿记空答案；</li>
 *   <li>答案补发对账：扫描"已交卷但 answers 尚未落库"的答卷（MQ 发送失败/消费重试耗尽
 *       进死信等极端场景），重新投递交卷消息；消费端 casFillAnswers 幂等，不会重复写。</li>
 * </ol>
 *
 * <p>与考试状态机扫表同构：Spring @Scheduled 固定间隔 + 启动补偿扫表，
 * 不依赖外部调度中间件；单轮分批（500/批）防止大场面拖垮单次扫描。
 */
@Slf4j
@Service
public class ExamSweepService {

    private final ExamSubmissionMapper submissionMapper;
    private final ExamSubmitService submitService;
    private final ExamDraftService draftService;
    private final ExamSubmitSender sender;

    @Value("${exam.taking.sweep.batch-size:500}")
    private int batchSize;

    @Value("${exam.taking.sweep.max-rounds:10}")
    private int maxRounds;

    public ExamSweepService(ExamSubmissionMapper submissionMapper, ExamSubmitService submitService,
                            ExamDraftService draftService, ExamSubmitSender sender) {
        this.submissionMapper = submissionMapper;
        this.submitService = submitService;
        this.draftService = draftService;
        this.sender = sender;
    }

    /** 执行一轮兜底：超时强制交卷 + 答案补发对账。 */
    public int sweep() {
        int forced = forceSubmitOverdue();
        int republished = republishMissingAnswers();
        return forced + republished;
    }

    /** 定时扫表：默认每 10 秒一轮（exam.taking.sweep.fixed-delay-ms 可调）。 */
    @Scheduled(fixedDelayString = "${exam.taking.sweep.fixed-delay-ms:10000}",
            initialDelayString = "${exam.taking.sweep.initial-delay-ms:10000}")
    public void scheduledSweep() {
        sweep();
    }

    /** 启动扫表：补偿停机期间错过的超时交卷与答案补发。 */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        sweep();
    }

    /** 超时/考试结束强制交卷：分批多轮扫表（竞态幂等，重复扫描无害）。 */
    private int forceSubmitOverdue() {
        int total = 0;
        for (int round = 0; round < maxRounds; round++) {
            List<ExamSubmission> candidates =
                    submissionMapper.selectForceSubmitCandidates(LocalDateTime.now(), batchSize);
            if (candidates.isEmpty()) {
                break;
            }
            for (ExamSubmission submission : candidates) {
                try {
                    submitService.forceSubmitByBackend(submission.getExamId(), submission.getStudentId());
                    total++;
                } catch (Exception e) {
                    // 并发窗口内已被其他路径提交/尚未进入等业务竞态：幂等跳过，不打断同批其他答卷
                    log.debug("兜底强制交卷跳过: exam={} student={} 原因={}",
                            submission.getExamId(), submission.getStudentId(), e.getMessage());
                }
            }
            if (candidates.size() < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("超时兜底强制交卷 {} 份", total);
        }
        return total;
    }

    /** 已交卷但答案未落库的补发对账（单轮一批，消费端幂等，下一轮继续补齐）。 */
    private int republishMissingAnswers() {
        List<ExamSubmission> missing = submissionMapper.selectSubmittedWithoutAnswers(batchSize);
        if (missing.isEmpty()) {
            return 0;
        }
        int republished = 0;
        for (ExamSubmission submission : missing) {
            try {
                sender.send(new SubmitMessage(
                        submission.getId(), submission.getExamId(), submission.getStudentId(),
                        Objects.requireNonNullElse(submission.getSubmitType(), ExamSubmission.SUBMIT_TYPE_BACKEND),
                        submission.getSubmitTime() == null ? LocalDateTime.now() : submission.getSubmitTime(),
                        draftAnswersOrEmpty(submission)));
                republished++;
            } catch (Exception e) {
                log.error("答案补发失败（等待下轮重试）: submission={}", submission.getId(), e);
            }
        }
        log.warn("答案补发对账: 待补={} 已补={}（已交卷未落库的答卷重新投递）", missing.size(), republished);
        return republished;
    }

    /** 补发答案来源：Redis 草稿（最后自动保存）优先，无草稿记空答案。 */
    private String draftAnswersOrEmpty(ExamSubmission submission) {
        ExamDraftService.DraftState draft =
                draftService.get(submission.getExamId(), submission.getStudentId());
        if (draft != null && draft.answers() != null && draft.answers().isObject()) {
            return draft.answers().toString();
        }
        return "{}";
    }
}
