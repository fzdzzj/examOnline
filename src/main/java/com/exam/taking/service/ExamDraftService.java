package com.exam.taking.service;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.taking.dto.AutoSaveRequest;
import com.exam.taking.dto.AutoSaveResponse;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 答题草稿服务（spec「自动保存与断线恢复」需求）：
 *
 * <ul>
 *   <li>自动保存：前端每 30s 推送一次答案到 Redis（key: exam:draft:{examId}:{studentId}），
 *       不落库——最终答案以交卷为准，草稿只服务断线恢复与后端超时兜底；</li>
 *   <li>断线恢复：重进考试时返回草稿，学生从上次保存点继续作答；</li>
 *   <li>多端冲突合并：以版本号与时间戳最新者为准——旧版本写入被拒绝并记录冲突日志，
 *       响应回服务端当前版本，客户端重新拉取合并（spec「多端冲突以最新为准」场景）。</li>
 * </ul>
 */
@Slf4j
@Service
public class ExamDraftService {

    static final String KEY_PREFIX = "exam:draft:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExamBehaviorLogService behaviorLogService;
    private final ExamSubmissionMapper submissionMapper;

    @Value("${exam.taking.draft.ttl-hours:2}")
    private int ttlHours;

    public ExamDraftService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                            ExamBehaviorLogService behaviorLogService, ExamSubmissionMapper submissionMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.behaviorLogService = behaviorLogService;
        this.submissionMapper = submissionMapper;
    }

    /**
     * 保存草稿：版本单调递增才受理；低于服务端版本的写入判定为多端冲突，拒绝并记录行为日志。
     * 同版本重复保存视为客户端重试，允许覆盖（最新写入即最新状态）。
     */
    public AutoSaveResponse save(Long examId, Long studentId, AutoSaveRequest request) {
        ExamSubmission submission = submissionMapper.selectByExamStudent(examId, studentId);
        if (submission == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "尚未进入考试，无法自动保存");
        }
        if (submission.getStatus() != ExamSubmission.STATUS_IN_PROGRESS) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "答卷已提交，草稿不再受理");
        }

        DraftState stored = get(examId, studentId);
        int incoming = request.getVersion() == null || request.getVersion() < 1 ? 1 : request.getVersion();
        if (stored != null && incoming < stored.version()) {
            // 多端冲突：以版本号最新者为准，旧端写入被拒并记录冲突日志（spec 场景）
            log.warn("草稿版本冲突: exam={} student={} incoming={} stored={}（拒绝旧版本写入）",
                    examId, studentId, incoming, stored.version());
            behaviorLogService.record(examId, studentId, "DRAFT_CONFLICT",
                    "{\"incomingVersion\":" + incoming + ",\"storedVersion\":" + stored.version() + "}",
                    1, LocalDateTime.now());
            return new AutoSaveResponse(false, stored.version(), stored.savedTime());
        }

        int acceptedVersion = stored == null ? incoming : incoming;
        LocalDateTime now = LocalDateTime.now();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", acceptedVersion);
        root.set("answers", request.getAnswers() == null ? objectMapper.createObjectNode() : request.getAnswers());
        ArrayNode marked = root.putArray("marked");
        if (request.getMarked() != null) {
            request.getMarked().forEach(marked::add);
        }
        root.put("savedTime", now.toString());

        redisTemplate.opsForValue().set(key(examId, studentId), root.toString(), ttlOf(submission));
        return new AutoSaveResponse(true, acceptedVersion, now);
    }

    /** 读取草稿（断线恢复/超时兜底答案来源）；无草稿或数据损坏返回 null。 */
    public DraftState get(Long examId, Long studentId) {
        String json = redisTemplate.opsForValue().get(key(examId, studentId));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            List<Long> marked = new ArrayList<>();
            JsonNode markedNode = root.get("marked");
            if (markedNode != null && markedNode.isArray()) {
                markedNode.forEach(n -> marked.add(n.asLong()));
            }
            LocalDateTime savedTime = root.hasNonNull("savedTime")
                    ? LocalDateTime.parse(root.get("savedTime").asText()) : null;
            return new DraftState(root.path("version").asInt(1), root.get("answers"), marked, savedTime);
        } catch (Exception e) {
            log.error("草稿数据损坏，按无草稿处理: exam={} student={}", examId, studentId, e);
            return null;
        }
    }

    /**
     * 交卷兜底：MQ 发送失败时把原始答案 JSON 覆盖进草稿，
     * 供对账补发扫描器重新投递消息（spec 交卷可靠性的自愈路径）。
     */
    public void overwriteAnswers(Long examId, Long studentId, String answersJson) {
        DraftState stored = get(examId, studentId);
        JsonNode answers;
        try {
            answers = objectMapper.readTree(answersJson);
        } catch (Exception e) {
            answers = objectMapper.createObjectNode();
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", stored == null ? 1 : stored.version() + 1);
        root.set("answers", answers);
        ArrayNode marked = root.putArray("marked");
        if (stored != null) {
            stored.marked().forEach(marked::add);
        }
        root.put("savedTime", LocalDateTime.now().toString());
        ExamSubmission submission = submissionMapper.selectByExamStudent(examId, studentId);
        redisTemplate.opsForValue().set(key(examId, studentId), root.toString(),
                submission == null ? Duration.ofHours(ttlHours) : ttlOf(submission));
    }

    /** TTL = 个人截止时间 + ttlHours 基数：保证截止后一段时间内兜底扫描仍能取到答案。 */
    private Duration ttlOf(ExamSubmission submission) {
        Duration untilDeadline = Duration.between(LocalDateTime.now(), submission.getDeadlineTime());
        return Duration.ofHours(ttlHours).plus(untilDeadline.isNegative() ? Duration.ZERO : untilDeadline);
    }

    private String key(Long examId, Long studentId) {
        return KEY_PREFIX + examId + ":" + studentId;
    }

    /** 草稿快照：版本号 + 答案 + 标记题 + 服务端受理时间。 */
    public record DraftState(int version, JsonNode answers, List<Long> marked, LocalDateTime savedTime) {
    }
}
