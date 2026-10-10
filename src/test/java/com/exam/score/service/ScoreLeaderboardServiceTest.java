package com.exam.score.service;

import com.exam.clazz.entity.UserClass;
import com.exam.clazz.mapper.UserClassMapper;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamCandidate;
import com.exam.exam.mapper.ExamCandidateMapper;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.score.dto.ScoreLeaderboardResponse;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 班级匿名榜单单元测试（add-class-leaderboard）：
 * 覆盖：准入403、发布门控404、无成绩404、并列跳号、匿名脱敏、本人两种形态、LIMIT 边界。
 */
@ExtendWith(MockitoExtension.class)
class ScoreLeaderboardServiceTest {

    private static final long EXAM_ID = 100L;
    private static final long CLASS_ID = 200L;
    private static final long STUDENT_ME = 1L;
    private static final long STUDENT_OTHER = 2L;

    @Mock
    private ExamMapper examMapper;
    @Mock
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Mock
    private ExamSubmissionMapper submissionMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private UserClassMapper userClassMapper;
    @Mock
    private ExamCandidateMapper examCandidateMapper;

    private RankCalculator rankCalculator;
    private ScoreLeaderboardService leaderboardService;

    @BeforeEach
    void setUp() {
        rankCalculator = new RankCalculator();
        leaderboardService = new ScoreLeaderboardService(
                examMapper,
                gradingSubmissionMapper,
                submissionMapper,
                userMapper,
                rankCalculator,
                userClassMapper,
                examCandidateMapper
        );
    }

    private Exam publishedExam() {
        Exam exam = new Exam();
        exam.setId(EXAM_ID);
        exam.setTitle("期末考试");
        exam.setClassId(CLASS_ID);
        exam.setStatus(Exam.STATUS_PUBLISHED);
        exam.setPublished(1);
        return exam;
    }

    private GradingSubmission gradedSubmission(long studentId, String score) {
        GradingSubmission sub = new GradingSubmission();
        sub.setExamId(EXAM_ID);
        sub.setStudentId(studentId);
        sub.setTotalScore(new BigDecimal(score));
        sub.setStatus(ExamSubmission.STATUS_GRADED);
        return sub;
    }

    private User user(long id, String name) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        return u;
    }

    @Test
    void leaderboardRejectsUnauthenticatedCaller() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> leaderboardService.leaderboard(EXAM_ID, null));
        assertEquals(ResponseCode.TOKEN_INVALID.getCode(), e.getCode());
    }

    @Test
    void leaderboardRejectsNonExistentExam() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> leaderboardService.leaderboard(EXAM_ID, STUDENT_ME));
        assertEquals(ResponseCode.NOT_FOUND.getCode(), e.getCode());
        assertEquals("考试不存在", e.getMessage());
    }

    @Test
    void leaderboardRejectsNonExamineeWith403() {
        Exam exam = publishedExam();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam);
        when(submissionMapper.selectCount(any())).thenReturn(0L);
        when(userClassMapper.selectCount(any())).thenReturn(0L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> leaderboardService.leaderboard(EXAM_ID, STUDENT_ME));
        assertEquals(ResponseCode.FORBIDDEN.getCode(), e.getCode());
        assertEquals(403, e.getHttpStatus());
        assertTrue(e.getMessage().contains("非本场考试考生"));
    }

    @Test
    void leaderboardRejectsUnpublishedExamWith404() {
        Exam exam = publishedExam();
        exam.setStatus(Exam.STATUS_GRADED); // 未发布
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam);
        when(submissionMapper.selectCount(any())).thenReturn(1L); // 是考生

        BusinessException e = assertThrows(BusinessException.class,
                () -> leaderboardService.leaderboard(EXAM_ID, STUDENT_ME));
        assertEquals(ResponseCode.NOT_FOUND.getCode(), e.getCode());
        assertEquals(404, e.getHttpStatus());
        assertEquals("暂无本人成绩记录", e.getMessage());
    }

    @Test
    void leaderboardRejectsWhenOwnRecordIsMissingWith404() {
        Exam exam = publishedExam();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam);
        when(submissionMapper.selectCount(any())).thenReturn(1L);
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> leaderboardService.leaderboard(EXAM_ID, STUDENT_ME));
        assertEquals(ResponseCode.NOT_FOUND.getCode(), e.getCode());
        assertEquals("暂无本人成绩记录", e.getMessage());
    }

    @Test
    void leaderboardRejectsWhenOwnRecordHasNoScoreWith404() {
        Exam exam = publishedExam();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam);
        when(submissionMapper.selectCount(any())).thenReturn(1L);
        GradingSubmission sub = new GradingSubmission();
        sub.setExamId(EXAM_ID);
        sub.setStudentId(STUDENT_ME);
        sub.setStatus(ExamSubmission.STATUS_SUBMITTED);
        sub.setTotalScore(null);
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(sub);

        BusinessException e = assertThrows(BusinessException.class,
                () -> leaderboardService.leaderboard(EXAM_ID, STUDENT_ME));
        assertEquals(ResponseCode.NOT_FOUND.getCode(), e.getCode());
        assertEquals("暂无本人成绩记录", e.getMessage());
    }

    @Test
    void leaderboardReturnsTop10WithMeHighlightedWhenInTop10() {
        Exam exam = publishedExam();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam);
        when(submissionMapper.selectCount(any())).thenReturn(1L);

        GradingSubmission mySub = gradedSubmission(STUDENT_ME, "95.0");
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(mySub);

        GradingSubmission s1 = gradedSubmission(10L, "100.0");
        GradingSubmission s2 = gradedSubmission(STUDENT_ME, "95.0");
        GradingSubmission s3 = gradedSubmission(12L, "95.0"); // 并列
        GradingSubmission s4 = gradedSubmission(13L, "80.0"); // 跳号至第 4 名

        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of(s1, s2, s3, s4));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                user(10L, "张三"),
                user(STUDENT_ME, "李四"),
                user(12L, "王五"),
                user(13L, "赵六")
        ));

        ScoreLeaderboardResponse resp = leaderboardService.leaderboard(EXAM_ID, STUDENT_ME);

        assertEquals(EXAM_ID, resp.getExamId());
        assertEquals("期末考试", resp.getExamTitle());
        assertNull(resp.getMyRow(), "本人在前 10 内时 myRow 应为 null");
        assertEquals(4, resp.getTop().size());

        // 核对名次与并列跳号（1, 2, 2, 4）
        assertEquals(1, resp.getTop().get(0).getRank());
        assertEquals(2, resp.getTop().get(1).getRank());
        assertEquals(2, resp.getTop().get(2).getRank());
        assertEquals(4, resp.getTop().get(3).getRank());

        // 核对姓名脱敏与本人实名
        assertEquals("张**", resp.getTop().get(0).getDisplayName());
        assertFalse(resp.getTop().get(0).getIsMe());

        assertEquals("李四", resp.getTop().get(1).getDisplayName(), "本人行应为实名");
        assertTrue(resp.getTop().get(1).getIsMe(), "本人行 isMe 应为 true");

        assertEquals("王**", resp.getTop().get(2).getDisplayName());
        assertFalse(resp.getTop().get(2).getIsMe());

        assertEquals("赵**", resp.getTop().get(3).getDisplayName());
        assertFalse(resp.getTop().get(3).getIsMe());
    }

    @Test
    void leaderboardReturnsMyRowWhenNotInTop10() {
        Exam exam = publishedExam();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam);
        when(submissionMapper.selectCount(any())).thenReturn(1L);

        GradingSubmission mySub = gradedSubmission(STUDENT_ME, "60.0");
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(mySub);

        // 前 10 名学生全不是本人
        List<GradingSubmission> top10 = List.of(
                gradedSubmission(11L, "99.0"), gradedSubmission(12L, "98.0"),
                gradedSubmission(13L, "97.0"), gradedSubmission(14L, "96.0"),
                gradedSubmission(15L, "95.0"), gradedSubmission(16L, "94.0"),
                gradedSubmission(17L, "93.0"), gradedSubmission(18L, "92.0"),
                gradedSubmission(19L, "91.0"), gradedSubmission(20L, "90.0")
        );
        when(gradingSubmissionMapper.selectList(any())).thenReturn(top10);
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                user(11L, "钱一"), user(12L, "钱二"), user(13L, "钱三"), user(14L, "钱四"),
                user(15L, "钱五"), user(16L, "钱六"), user(17L, "钱七"), user(18L, "钱八"),
                user(19L, "钱九"), user(20L, "钱十")
        ));
        // 全班有 14 人比我分高 -> 我排第 15 名
        when(gradingSubmissionMapper.selectCount(any())).thenReturn(14L);

        ScoreLeaderboardResponse resp = leaderboardService.leaderboard(EXAM_ID, STUDENT_ME);

        assertEquals(10, resp.getTop().size());
        assertTrue(resp.getTop().stream().noneMatch(item -> Boolean.TRUE.equals(item.getIsMe())));

        assertNotNull(resp.getMyRow(), "本人不在前 10 时应返回 myRow");
        assertEquals(15, resp.getMyRow().getRank());
        assertEquals(new BigDecimal("60.0"), resp.getMyRow().getTotalScore());
        assertTrue(resp.getMyRow().getIsMe());
    }

    @Test
    void maskNameMasksCorrectly() {
        assertEquals("张**", ScoreLeaderboardService.maskName("张三"));
        assertEquals("李**", ScoreLeaderboardService.maskName("李四五"));
        assertEquals("欧**", ScoreLeaderboardService.maskName("欧阳六"));
        assertEquals("王**", ScoreLeaderboardService.maskName("王"));
        assertEquals("某**", ScoreLeaderboardService.maskName(""));
        assertEquals("某**", ScoreLeaderboardService.maskName(null));
    }
}
