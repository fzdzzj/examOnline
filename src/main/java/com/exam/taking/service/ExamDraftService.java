package com.exam.taking.service;

import com.exam.anticheat.collector.BehaviorEventTypes;
import com.exam.anticheat.service.BehaviorEventCollectService;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.monitoring.service.OnlinePresenceService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final BehaviorEventCollectService eventCollectService;
    private final ExamSubmissionMapper submissionMapper;
    private final OnlinePresenceService presenceService;

    @Value("${exam.taking.draft.ttl-hours:2}")
    private int ttlHours;

    public ExamDraftService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                            BehaviorEventCollectService eventCollectService, ExamSubmissionMapper submissionMapper,
                            OnlinePresenceService presenceService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.eventCollectService = eventCollectService;
        this.submissionMapper = submissionMapper;
        this.presenceService = presenceService;
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
            // 阶段 7：冲突事件交由防作弊采集核心（策略模式）统一判定严重度并落库
            ObjectNode conflictData = objectMapper.createObjectNode();
            conflictData.put("incomingVersion", incoming);
            conflictData.put("storedVersion", stored.version());
            eventCollectService.collect(examId, studentId, BehaviorEventTypes.DRAFT_CONFLICT,
                    conflictData, LocalDateTime.now());
            return new AutoSaveResponse(false, stored.version(), stored.savedTime());
        }

        int acceptedVersion = incoming;
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
        // 30s 自动保存即监考在线心跳（阶段 7：大屏在线/离线判定的数据源）
        presenceService.touch(examId, studentId);
        return new AutoSaveResponse(true, acceptedVersion, now);
    }

    /** 读取草稿（断线恢复/超时兜底答案来源）；无草稿或数据损坏返回 null。 */
    public DraftState get(Long examId, Long studentId) {
        return parseDraft(examId, studentId, redisTemplate.opsForValue().get(key(examId, studentId)));
    }

    /**
     * 批量读取草稿（监考大屏等只读聚合场景）：一次 MGET 取回全部学生，逐值走与 {@link #get}
     * 完全相同的解析口径（{@link #parseDraft}）并按入参下标与学生对齐。
     *
     * <p>返回 Map 仅含"有草稿且解析成功"的学生；入参为空直接返回不触达 Redis。
     * Redis 读取异常不吞（向上抛）；MGET 结果缺失或长度无法与请求学生一一对齐时抛错，
     * 绝不把读取失败/错位伪装成"无草稿/全员零进度"。
     */
    public Map<Long, DraftState> getBatch(Long examId, List<Long> studentIds) {
        if (studentIds == null || studentIds.isEmpty()) {
            return Map.of();
        }
        List<String> keys = new ArrayList<>(studentIds.size());
        for (Long studentId : studentIds) {
            keys.add(key(examId, studentId));
        }
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null || values.size() != studentIds.size()) {
            throw new IllegalStateException("草稿批量读取结果无法与请求学生对齐: exam=" + examId
                    + " requested=" + studentIds.size()
                    + " returned=" + (values == null ? "null" : String.valueOf(values.size())));
        }
        Map<Long, DraftState> drafts = new HashMap<>(studentIds.size());
        for (int i = 0; i < studentIds.size(); i++) {
            DraftState state = parseDraft(examId, studentIds.get(i), values.get(i));
            if (state != null) {
                drafts.put(studentIds.get(i), state);
            }
        }
        return drafts;
    }

    /** 单份草稿 JSON → 快照；null/空白按无草稿、解析失败按损坏（记日志）返回 null。 */
    private DraftState parseDraft(Long examId, Long studentId, String json) {
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
