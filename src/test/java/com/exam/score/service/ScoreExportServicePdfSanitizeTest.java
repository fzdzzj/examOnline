package com.exam.score.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 个人成绩单 PDF 导出生僻/特殊字符容错护栏（harden-review-flow-and-resource-limits L-02）：
 *
 * <p>STSong-Light + UniGB-UCS2-H 是 GB2312 映射字体（CMap 表长 65536），
 * 遇增补平面字符（Emoji、CJK 扩展 B 生僻字）时 openpdf 抛
 * {@code ArrayIndexOutOfBoundsException} 中断整个导出（本仓实测复现）。
 *
 * <p>本类仅引用公开 API（{@code exportPersonalPdf}）——在实现前（或变异 m3
 * 抽掉清洗后）可正常编译，仅「导出抛异常」变红，是 m3 击红的指定载体。
 * {@link ScoreExportService#sanitizePdfText} 的单元断言见
 * {@link ScoreExportServiceSanitizePdfTextUnitTest}。
 */
@ExtendWith(MockitoExtension.class)
class ScoreExportServicePdfSanitizeTest {

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

    @BeforeEach
    void initEntityMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, GradingSubmission.class);
        service = new ScoreExportService(examMapper, gradingSubmissionMapper, paperReader,
                scoreResolver, userMapper, rankCalculator);
        // assertExportable 走 OwnershipGuard：以 ADMIN（层级 3）身份放行
        LoginUser admin = new LoginUser();
        admin.setId(1L);
        admin.setRoleLevel(3);
        admin.setRoles(Set.of("ADMIN"));
        SecurityUtil.set(admin);
    }

    @AfterEach
    void clearSecurity() {
        SecurityUtil.clear();
    }

    // ==================== 端到端：含 Emoji 的题干/标题导出不中断（m3 敏感） ====================

    @Test
    @DisplayName("题干与标题含 Emoji 时个人成绩单 PDF 仍正常生成")
    void exportPersonalPdf_withUnsupportedChars_stillGeneratesPdf() {
        Exam exam = new Exam();
        exam.setId(EXAM_ID);
        exam.setTitle("期中\uD83D\uDE00考试"); // 标题含 Emoji
        exam.setStatus(Exam.STATUS_GRADED);

        GradingQuestion q1 = new GradingQuestion(1, 11L, QuestionType.SINGLE,
                "题干含 Emoji\uD83D\uDE03与数学符号∑∅，作答为单选", null,
                List.of("A", "B"), "A", new BigDecimal("4.0"));
        GradingPaper paper = new GradingPaper(900L, "试卷", new BigDecimal("4.0"), List.of(q1));

        GradingSubmission submission = new GradingSubmission();
        submission.setId(555L);
        submission.setExamId(EXAM_ID);
        submission.setStudentId(STUDENT_ID);
        submission.setStatus(ExamSubmission.STATUS_GRADED);
        submission.setTotalScore(new BigDecimal("4.0"));
        submission.setObjectiveScore(new BigDecimal("4.0"));
        submission.setSubjectiveScore(BigDecimal.ZERO);

        when(examMapper.selectById(EXAM_ID)).thenReturn(exam);
        when(paperReader.readByExamId(EXAM_ID)).thenReturn(paper);
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(submission);
        when(scoreResolver.resolve(submission, paper)).thenReturn(Map.of(11L,
                new QuestionScoreResolver.ResolvedQuestionScore(new BigDecimal("4.0"),
                        new BigDecimal("4.0"), true, true, null, "评语\uD83D\uDE00正确")));
        when(paperReader.parseAnswers(any())).thenReturn(Map.of(11L, "A"));
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of(submission));
        when(rankCalculator.rank(any())).thenReturn(new int[]{1});
        when(userMapper.selectById(STUDENT_ID)).thenReturn(user());

        byte[] pdf = service.exportPersonalPdf(EXAM_ID, STUDENT_ID);

        assertNotNull(pdf, "PDF 必须成功生成");
        assertTrue(pdf.length > 0, "PDF 字节非空");
        // PDF 魔数 %PDF
        assertEquals('%', (char) pdf[0]);
    }

    private static User user() {
        User u = new User();
        u.setId(STUDENT_ID);
        u.setUsername("stu7");
        u.setName("学生7");
        return u;
    }
}
