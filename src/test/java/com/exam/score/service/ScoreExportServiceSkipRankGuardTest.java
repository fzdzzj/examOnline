package com.exam.score.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.support.GradingPaperReader;
import com.exam.question.entity.QuestionType;
import com.exam.score.support.QuestionScoreResolver;
import com.exam.submission.entity.ExamSubmission;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 候选 5（update-wrong-question-list-skip-rank）护栏单测：
 *
 * <p>①轻量入口 {@link ScoreExportService#getPersonalReportWithoutRank} 对全班排名与用户取数零交互
 * （RankCalculator 零交互 + userMapper.selectById 零调用）——构造性实证：列表路径不再触发
 * 「全班投影查询 + 全班排名计算」；
 *
 * <p>②轻量入口与完整入口 {@link ScoreExportService#getPersonalReport} 的判分三要素
 * （questions/scores/answers）一致——判分同源护栏：两入口共用同一判分核心装配，绝不另造第二套口径。
 */
@ExtendWith(MockitoExtension.class)
class ScoreExportServiceSkipRankGuardTest {

    private static final long EXAM_ID = 100L;
    private static final long STUDENT_ID = 7L;

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

    private ScoreExportService service;

    private final Exam exam = exam();

    private final GradingQuestion q1 = new GradingQuestion(1, 11L, QuestionType.SINGLE,
            "单选1", null, java.util.List.of("A", "B"), "A", new BigDecimal("4.0"));
    private final GradingQuestion q2 = new GradingQuestion(2, 12L, QuestionType.SINGLE,
            "单选2", null, java.util.List.of("C", "D"), "C", new BigDecimal("6.0"));
    private final GradingPaper paper = new GradingPaper(900L, "试卷", new BigDecimal("10.0"),
            java.util.List.of(q1, q2));
    private final GradingSubmission submission = gradedSubmission();

    private final Map<Long, QuestionScoreResolver.ResolvedQuestionScore> scores = Map.of(
            11L, new QuestionScoreResolver.ResolvedQuestionScore(
                    new BigDecimal("4.0"), new BigDecimal("4.0"), true, true, null, "正确"),
            12L, new QuestionScoreResolver.ResolvedQuestionScore(
                    new BigDecimal("3.0"), new BigDecimal("6.0"), false, true, null, "漏选"));
    private final Map<Long, String> answers = Map.of(11L, "A", 12L, "C");

    @BeforeEach
    void initEntityMetadata() {
        // 纯 Mockito 环境无 MyBatis 启动流程，lambdaQuery().eq(GradingSubmission::getExamId, ...)
        // 在装配/取数阶段即解析列名，须手动初始化实体元数据缓存（先例：ObjectiveGradingServiceTest）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GradingSubmission.class);
    }

    @BeforeEach
    void setUp() {
        service = new ScoreExportService(examMapper, gradingSubmissionMapper, paperReader,
                scoreResolver, userMapper, rankCalculator);
    }

    // ==================== 构造性实证：轻量入口零交互 ====================

    @Test
    @DisplayName("轻量入口对 RankCalculator 零交互，且不取用户段")
    void lightEntry_doesNotTouchRankOrUser() {
        stubCoreAssembly();

        ScoreExportService.PersonalReport report =
                service.getPersonalReportWithoutRank(exam, STUDENT_ID);

        verifyNoInteractions(rankCalculator);
        verify(userMapper, never()).selectById(anyLong());
        verify(userMapper, never()).selectBatchIds(any());

        // 判分三要素齐备且与装配一致
        assertEquals(2, report.questions().size());
        assertEquals(scores, report.scores());
        assertEquals(answers, report.answers());
        assertEquals(q1, report.questions().get(0));
        assertEquals(q2, report.questions().get(1));
    }

    // ==================== 判分同源：轻量与完整口径一致 ====================

    @Test
    @DisplayName("轻量入口与完整入口判分三要素 questions/scores/answers 一致")
    void lightAndFullEntry_shareSameGradingCore() {
        stubCoreAssembly();
        // 完整入口额外依赖用户与排名两段（仅装配语义差异，不影响判分三要素）。
        when(userMapper.selectById(STUDENT_ID)).thenReturn(realUser());
        when(gradingSubmissionMapper.selectList(any())).thenReturn(java.util.List.of(submission));
        when(rankCalculator.rank(any())).thenReturn(new int[]{1});

        ScoreExportService.PersonalReport full =
                service.getPersonalReport(exam, STUDENT_ID);
        ScoreExportService.PersonalReport light =
                service.getPersonalReportWithoutRank(exam, STUDENT_ID);

        assertEquals(full.questions(), light.questions());
        assertEquals(full.scores(), light.scores());
        assertEquals(full.answers(), light.answers());
        // 完整口径名次/用户/汇总字段保持真实装配（轻量入口以占位填充，仅消费行为约束在列表路径）。
        assertEquals(1, full.rank());
        assertEquals("stu7", full.username());
    }

    // ==================== 辅助 ====================

    private void stubCoreAssembly() {
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paper);
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(submission);
        when(scoreResolver.resolve(submission, paper)).thenReturn(scores);
        when(paperReader.parseAnswers(any())).thenReturn(answers);
    }

    private static Exam exam() {
        Exam e = new Exam();
        e.setId(EXAM_ID);
        return e;
    }

    private static GradingSubmission gradedSubmission() {
        GradingSubmission s = new GradingSubmission();
        s.setId(555L);
        s.setExamId(EXAM_ID);
        s.setStudentId(STUDENT_ID);
        s.setStatus(ExamSubmission.STATUS_GRADED);
        s.setTotalScore(new BigDecimal("7.0"));
        s.setObjectiveScore(new BigDecimal("7.0"));
        s.setSubjectiveScore(BigDecimal.ZERO);
        return s;
    }

    private static User realUser() {
        User u = new User();
        u.setId(STUDENT_ID);
        u.setUsername("stu7");
        u.setName("学生7");
        return u;
    }
}