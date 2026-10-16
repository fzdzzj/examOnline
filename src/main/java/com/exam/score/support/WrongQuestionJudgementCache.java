package com.exam.score.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.config.CacheProperties;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.question.entity.QuestionType;
import com.exam.submission.entity.ExamSubmission;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 错题判定轻量缓存（optimize-wrong-question-derivation-cost）：
 *
 * <p>消费端语义：错题派生链（重练池列表 / 错题本 / 提交门控）只用 {@code score < max} 布尔判定，
 * 不必逐场全量装配个人成绩单（判分重算 + 全卷载入 + 双重 parseAnswers）。本缓存把
 * {@code {examId}:{studentId}} 的逐题判定三元组 {@code score/max/type} 缓存进 Redis，
 * 命中即免去一次 {@link ScoreExportService} 的 {@code assembleGradingCore}。
 *
 * <p><b>版本戳自失效（零写路径侵入，零 DDL）</b>：判分事实源 = 答卷 {@code exam_submissions}
 * 与主观批改 {@code subjective_grades}，两表 <b>已有</b> {@code updated_time} 时间戳与
 * {@code subjective_grades.version} 乐观锁列（侦察确认批改/复核写点均 touch 这两张表：
 * {@code SubjectiveGradingService.submitGrade} → {@code casSaveScore} 自增 version 并刷新
 * {@code updated_time}，随后 {@code refreshSubmissionSubjectiveScore} 显式刷答卷
 * {@code updated_time}）。缓存 key 携带版本信号：
 * {@code concat(submission.updated_time, MAX(subjective.updated_time), MAX(subjective.version))}。
 * 判分事实源变更后信号变化 → key 变化 → 旧缓存自然不被采信（天然失效），不触碰任何写路径，
 * 故白名单可收窄在 score 包，无需部署到 grading 写点。
 *
 * <p><b>内容红线</b>：只存 {@code score/max/type}，不含答案文本、评语、判分依据明细。
 * 成绩单导出仍走完整装配，本缓存不替代导出路径。
 *
 * <p><b>可用性优先</b>：Redis 任何故障（连接/序列化/读/写）一律 try/catch 捕获并退化为
 * 现行现算（loader 兜底），不得因缓存故障阻断重练池访问。
 */
@Slf4j
@Component
public class WrongQuestionJudgementCache {

    /** Redis key 前缀，与既有缓存命名一致：exam:cache:{cacheName}:{key} */
    static final String KEY_PREFIX = "exam:cache:wrongQuestionJudgement:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final SubjectiveGradeMapper subjectiveGradeMapper;
    private final Duration ttl;

    /**
     * 逐题轻量判定三元组：score=得分、max=满分、type=题目题型（消费端据此做 score<max 判定
     * 与简答排除，与现算口径逐字一致，不引入第二套判分逻辑）。
     */
    public record ScoredQuestion(BigDecimal score, BigDecimal max, QuestionType type) {
    }

    public WrongQuestionJudgementCache(StringRedisTemplate redis,
                                       ObjectMapper objectMapper,
                                       GradingSubmissionMapper gradingSubmissionMapper,
                                       SubjectiveGradeMapper subjectiveGradeMapper,
                                       CacheProperties cacheProperties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.subjectiveGradeMapper = subjectiveGradeMapper;
        this.ttl = cacheProperties.getWrongQuestionJudgementTtl();
    }

    /**
     * 读取 {@code (examId, studentId)} 的逐题轻量判定结果。
     *
     * @param loader 未命中（或 Redis 故障）时的回填器：返回 null 表示该场无判定数据（消费端跳过），
     *               与现行「装配异常跳过该场」语义一致。
     * @return questionId → (score, max, type)；无该场判定数据或装配异常返回 null。
     */
    public Map<Long, ScoredQuestion> get(Long examId, Long studentId,
                                         Supplier<Map<Long, ScoredQuestion>> loader) {
        try {
            String sig = versionSignal(examId, studentId);
            if (sig == null) {
                // 无已汇总答卷：按无判定数据处理（与调用方已过滤出的 published 场不冲突，双保险）
                return loader.get();
            }
            String key = key(examId, studentId, sig);
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                Map<Long, ScoredQuestion> hit = deserialize(cached);
                if (hit != null) {
                    return hit;
                }
                log.warn("错题判定缓存反序列化失败，按未命中回填: exam={} student={}", examId, studentId);
            }
            // 未命中：按现行现算口径回填（复用 loader，内部即 assembleGradingCore）
            Map<Long, ScoredQuestion> scored = loader.get();
            if (scored != null) {
                redis.opsForValue().set(key, serialize(scored), ttl);
            }
            return scored;
        } catch (Exception e) {
            // Redis 故障可用性优先：退化为现行现算（不写缓存也不报错），不得阻断重练池
            log.warn("错题判定缓存不可用，退化为现算: exam={} student={} 原因={}",
                    examId, studentId, e.getMessage());
            return loader.get();
        }
    }

    /** 缓存 key：携带判分事实源版本信号，事实源变更后 key 变化即天然失效。 */
    private String key(Long examId, Long studentId, String sig) {
        return KEY_PREFIX + examId + ":" + studentId + ":v" + sig;
    }

    /**
     * 计算判分事实源版本信号：答卷 updated_time + 主观批改最新 updated_time + 主观批改
     * 最大乐观锁 version。三者任一变化（批改/复核/重判/答卷变更）都会改变信号，使 key 变化
     * 从而不采信旧缓存。列均已存在（零 DDL）。
     */
    private String versionSignal(Long examId, Long studentId) {
        GradingSubmission submission = gradingSubmissionMapper.selectOne(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStudentId, studentId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED));
        if (submission == null || submission.getTotalScore() == null) {
            return null;
        }
        GradeStamp subjective = maxSubjectiveStamp(examId, studentId);
        return Long.toString(epochMillis(submission.getUpdatedTime()))
                + "|" + Long.toString(epochMillis(subjective.maxUpdatedTime))
                + "|" + Long.toString(subjective.maxVersion);
    }

    /** 主观批改最新变更信号聚合（updated_time 最大行 + 全行最大 version）。 */
    private GradeStamp maxSubjectiveStamp(Long examId, Long studentId) {
        LocalDateTime maxUpdated = null;
        int maxVersion = 0;
        java.util.List<SubjectiveGrade> rows = subjectiveGradeMapper.selectList(
                Wrappers.<SubjectiveGrade>lambdaQuery()
                        .eq(SubjectiveGrade::getExamId, examId)
                        .eq(SubjectiveGrade::getStudentId, studentId));
        for (SubjectiveGrade row : rows) {
            if (row.getUpdatedTime() != null
                    && (maxUpdated == null || row.getUpdatedTime().isAfter(maxUpdated))) {
                maxUpdated = row.getUpdatedTime();
            }
            if (row.getVersion() != null && row.getVersion() > maxVersion) {
                maxVersion = row.getVersion();
            }
        }
        return new GradeStamp(maxUpdated, maxVersion);
    }

    private record GradeStamp(LocalDateTime maxUpdatedTime, int maxVersion) {
    }

    private static long epochMillis(LocalDateTime time) {
        if (time == null) {
            return 0L;
        }
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private String serialize(Map<Long, ScoredQuestion> scored) throws com.fasterxml.jackson.core.JsonProcessingException {
        return objectMapper.writeValueAsString(scored);
    }

    private Map<Long, ScoredQuestion> deserialize(String json) throws java.io.IOException {
        return objectMapper.readValue(json, new TypeReference<HashMap<Long, ScoredQuestion>>() {
        });
    }

    /** 供单测断言缓存 key 前缀（密钥含版本信号，测试据此校验失效）。 */
    static String keyPrefix() {
        return KEY_PREFIX;
    }
}