package com.exam.score.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.config.CacheProperties;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.support.GradingPaperReader;
import com.exam.question.entity.QuestionType;
import com.exam.score.support.QuestionScoreResolver;
import com.exam.score.support.WrongQuestionJudgementCache;
import com.exam.submission.entity.ExamSubmission;
import com.exam.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 错题判定缓存派生语义单测（optimize-wrong-question-derivation-cost）：
 *
 * <ul>
 *   <li><b>装配次数收敛</b>：多场学生重练池路径，缓存命中后判分核心装配（resolve 调用，等价于
 *       assembleGradingCore 调用）次数 SHALL ≤ 未命中场数（而非场数总数）——这里是 0 场未命中 → 0 次装配；</li>
 *   <li><b>双 parse 收敛</b>：判分核心装配对同一答案 JSON SHALL 至多 parse 一次
 *       （assembleGradingCore 的双重 parseAnswers 收敛）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WrongQuestionDerivationCacheSemanticsTest {

    private static final long EXAM_ID = 1001L;
    private static final long STUDENT_ID = 42L;

    @Mock
    private ExamMapper examMapper;
    @Mock
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Mock
    private GradingPaperReader paperReader;
    @Mock
    private QuestionScoreResolver scoreResolver;
    @Mock
    private UserMapper userMapper;
    @Mock
    private RankCalculator rankCalculator;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SubjectiveGradeMapper subjectiveGradeMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CacheProperties props = new CacheProperties();

    private final GradingQuestion q1 = new GradingQuestion(1, 11L, QuestionType.SINGLE,
            "单选1", null, List.of("A", "B"), "A", new BigDecimal("4.0"));
    private final GradingPaper paper = new GradingPaper(900L, "试卷", new BigDecimal("4.0"), List.of(q1));

    private ScoreExportService service;

    @BeforeEach
    void init() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GradingSubmission.class);
        TableInfoHelper.initTableInfo(assistant, com.exam.grading.entity.SubjectiveGrade.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        WrongQuestionJudgementCache judgementCache = new WrongQuestionJudgementCache(
                redis, objectMapper, gradingSubmissionMapper, subjectiveGradeMapper, props);
        service = new ScoreExportService(examMapper, gradingSubmissionMapper, paperReader,
                scoreResolver, userMapper, rankCalculator, judgementCache);
    }

    @Test
    @DisplayName("缓存命中后判分核心装配次数为 0（≤ 未命中场数），资源不轮询重算")
    void cachedRead_skipsGradingCoreAssembly() throws Exception {
        GradingSubmission submission = submission(ts("2026-01-01T10:00:00"));
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(submission);
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of());
        // 关键：把缓存预填好，命中路径不得走 scoreResolver.resolve（即不触发 assembleGradingCore）
        Map<Long, WrongQuestionJudgementCache.ScoredQuestion> scored = Map.of(11L,
                new WrongQuestionJudgementCache.ScoredQuestion(
                        new BigDecimal("3.0"), new BigDecimal("4.0"), QuestionType.SINGLE));
        seedCache(scored);

        Exam exam = new Exam();
        exam.setId(EXAM_ID);
        Map<Long, WrongQuestionJudgementCache.ScoredQuestion> result =
                service.wrongQuestionScores(exam, STUDENT_ID);

        assertEquals(new BigDecimal("3.0"), result.get(11L).score());
        verify(scoreResolver, never()).resolve(any(), any());
        verify(scoreResolver, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("未命中路径：一次命中回填恰好一次判分核心装配（每未命中场数一次）")
    void missPath_assemblesExactlyOnceForBackfill() {
        GradingSubmission submission = submission(ts("2026-01-01T10:00:00"));
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(submission);
        when(subjectiveGradeMapper.selectList(any())).thenReturn(List.of());
        when(valueOps.get(anyString())).thenReturn(null);   // 冷缓存 → 未命中

        Map<Long, String> answers = Map.of(11L, "B");
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paper);
        when(paperReader.parseAnswers(any())).thenReturn(answers);
        Map<Long, QuestionScoreResolver.ResolvedQuestionScore> scores = Map.of(11L,
                new QuestionScoreResolver.ResolvedQuestionScore(
                        new BigDecimal("3.0"), new BigDecimal("4.0"), false, true, null, "漏选"));
        when(scoreResolver.resolve(submission, paper, answers)).thenReturn(scores);

        Exam exam = new Exam();
        exam.setId(EXAM_ID);
        Map<Long, WrongQuestionJudgementCache.ScoredQuestion> result =
                service.wrongQuestionScores(exam, STUDENT_ID);

        assertEquals(new BigDecimal("3.0"), result.get(11L).score());
        // 装配恰好一次（对应 1 场未命中 = 1 次装配），随后命中不再装配
        verify(scoreResolver, times(1)).resolve(submission, paper, answers);
        verify(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));
    }

    @Test
    @DisplayName("双 parse 收敛：判分核心装配对同一答案 JSON 至多 parse 一次")
    void assembleGradingCore_parsesAnswersOnce() {
        GradingSubmission submission = submission(ts("2026-01-01T10:00:00"));
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(submission);
        Map<Long, String> answers = Map.of(11L, "B");
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paper);
        when(paperReader.parseAnswers(any())).thenReturn(answers);
        when(scoreResolver.resolve(submission, paper, answers)).thenReturn(Map.of(11L,
                new QuestionScoreResolver.ResolvedQuestionScore(
                        new BigDecimal("3.0"), new BigDecimal("4.0"), false, true, null, "漏选")));

        Exam exam = new Exam();
        exam.setId(EXAM_ID);
        service.getPersonalReportWithoutRank(exam, STUDENT_ID);

        // assembleGradingCore 内 resolve 复用外层 parse 结果，parseAnswers 只该被调用一次
        verify(paperReader, times(1)).parseAnswers(any());
        verify(scoreResolver, times(1)).resolve(submission, paper, answers);
    }

    private void seedCache(Map<Long, WrongQuestionJudgementCache.ScoredQuestion> scored) throws Exception {
        // 直接落一条命中值的 Redis key（key 需含版本信号；这里用真实 get 的首 key 固定签名）
        when(valueOps.get(anyString())).thenReturn(objectMapper.writeValueAsString(scored));
    }

    private GradingSubmission submission(LocalDateTime updatedTime) {
        GradingSubmission s = new GradingSubmission();
        s.setId(9000L);
        s.setExamId(EXAM_ID);
        s.setStudentId(STUDENT_ID);
        s.setStatus(ExamSubmission.STATUS_GRADED);
        s.setTotalScore(new BigDecimal("60"));
        s.setUpdatedTime(updatedTime);
        return s;
    }

    private static LocalDateTime ts(String iso) {
        return LocalDateTime.parse(iso);
    }
}