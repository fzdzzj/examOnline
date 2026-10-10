package com.exam.score.service;

import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.score.dto.MyScoreResponse;
import com.exam.score.dto.ScorePreviewResponse;
import com.exam.submission.entity.ExamSubmission;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 发布前预览 / 学生查分单测（spec score-management，决策记录 §5.3/§9.10）。
 *
 * <p>自 ScoreServiceTest 读侧 10 例整体迁入（split-score-query-service）：被测对象改为
 * {@link ScoreQueryService}，用例体与造数基建保持原文，仅注入目标随读侧拆出同步改指新类。
 */
@ExtendWith(MockitoExtension.class)
class ScoreQueryServiceTest {

    private static final Long TEACHER_ID = 7L;
    private static final Long EXAM_ID = 10L;

    @Mock
    private ExamMapper examMapper;
    @Mock
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private RankCalculator rankCalculator;
    @Mock
    private ScoreReviewService scoreReviewService;

    @InjectMocks
    private ScoreQueryService scoreQueryService;

    @AfterEach
    void clearSecurity() {
        SecurityUtil.clear();
    }

    // ==================== 造数工具 ====================

    private Exam exam(long id, int status) {
        Exam exam = new Exam();
        exam.setId(id);
        exam.setTitle("期末考-" + id);
        exam.setStatus(status);
        exam.setVersion(0);
        exam.setCreatedBy(TEACHER_ID);
        return exam;
    }

    private GradingSubmission submission(long id, long studentId, String objective, String total,
                                         Integer partialGraded) {
        GradingSubmission submission = new GradingSubmission();
        submission.setId(id);
        submission.setExamId(EXAM_ID);
        submission.setStudentId(studentId);
        submission.setObjectiveScore(objective == null ? null : new BigDecimal(objective));
        submission.setTotalScore(total == null ? null : new BigDecimal(total));
        submission.setPartialGraded(partialGraded);
        submission.setGradingStatus(1);
        return submission;
    }

    private User user(long id, String name) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        return user;
    }

    private void loginAs(Long userId, int roleLevel) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(userId);
        loginUser.setRoleLevel(roleLevel);
        SecurityUtil.set(loginUser);
    }

    private void loginAsTeacher() {
        loginAs(TEACHER_ID, RoleHierarchy.levelOf(RoleHierarchy.TEACHER));
    }

    private static String messageOf(ResponseCode rc) {
        return new BusinessException(rc).getMessage();
    }

    // ==================== 发布前预览 ====================

    @Test
    void publishPreviewRejectsBeforeSummarize() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_ENDED));

        assertEquals("成绩尚未汇总，请先执行汇总",
                assertThrows(BusinessException.class, () -> scoreQueryService.publishPreview(EXAM_ID)).getMessage());
    }

    @Test
    void publishPreviewRanksStudentsAndCountsPartialGraded() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_GRADED));
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of(
                submission(1L, 201L, "90.0", "90.0", 0),
                submission(2L, 202L, "88.0", "88.0", 1),
                submission(3L, 203L, "88.0", "88.0", null)));
        // 203 号学生查不到 → 兜底"未知学生"，不整表失败
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(201L, "张三"), user(202L, "李四")));
        when(rankCalculator.rank(any())).thenReturn(new int[]{1, 2, 2});

        ScorePreviewResponse response = scoreQueryService.publishPreview(EXAM_ID);

        assertEquals(EXAM_ID, response.getExamId());
        assertEquals("期末考-" + EXAM_ID, response.getExamTitle());
        assertEquals(3, response.getSummarizedCount());
        assertEquals(1, response.getPartialGradedCount(), "partial_graded 为 null 的历史数据不计入");
        assertEquals(List.of("张三", "李四", "未知学生"),
                response.getItems().stream().map(item -> item.getStudentName()).toList());
        assertEquals(List.of(1, 2, 2), response.getItems().stream().map(item -> item.getRank()).toList());
    }

    @Test
    void publishPreviewStillWorksAfterPublishAndSkipsUserLookupOnEmptyBoard() {
        loginAsTeacher();
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_PUBLISHED));
        when(gradingSubmissionMapper.selectList(any())).thenReturn(List.of());

        ScorePreviewResponse response = scoreQueryService.publishPreview(EXAM_ID);

        assertEquals(0, response.getSummarizedCount());
        assertTrue(response.getItems().isEmpty());
        verifyNoInteractions(userMapper);
    }

    // ==================== 学生查成绩（异常口径） ====================

    @Test
    void myScoreRejectsUnauthenticatedCaller() {
        assertEquals(messageOf(ResponseCode.TOKEN_INVALID),
                assertThrows(BusinessException.class, () -> scoreQueryService.myScore(EXAM_ID)).getMessage());
    }

    @Test
    void myScoreRejectsMissingExam() {
        loginAs(201L, RoleHierarchy.levelOf(RoleHierarchy.STUDENT));
        when(examMapper.selectById(EXAM_ID)).thenReturn(null);

        assertEquals("考试不存在",
                assertThrows(BusinessException.class, () -> scoreQueryService.myScore(EXAM_ID)).getMessage());
    }

    @Test
    void myScoreShowsSamePendingMessageForGradedAndRevokedExam() {
        loginAs(201L, RoleHierarchy.levelOf(RoleHierarchy.STUDENT));
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_GRADED));

        BusinessException e = assertThrows(BusinessException.class, () -> scoreQueryService.myScore(EXAM_ID));

        assertEquals("成绩待发布", e.getMessage(), "撤回后不得泄露批改进度（§5.3 统一口径）");
        verifyNoInteractions(gradingSubmissionMapper);
    }

    @Test
    void myScoreRejectsWhenOwnRecordIsMissing() {
        loginAs(201L, RoleHierarchy.levelOf(RoleHierarchy.STUDENT));
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_PUBLISHED));
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(null);

        assertEquals("暂无本人成绩记录",
                assertThrows(BusinessException.class, () -> scoreQueryService.myScore(EXAM_ID)).getMessage());
    }

    @Test
    void myScoreRejectsWhenOwnRecordHasNoTotalScoreYet() {
        loginAs(201L, RoleHierarchy.levelOf(RoleHierarchy.STUDENT));
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_PUBLISHED));
        when(gradingSubmissionMapper.selectOne(any()))
                .thenReturn(submission(1L, 201L, "14.0", null, null));

        assertEquals("暂无本人成绩记录",
                assertThrows(BusinessException.class, () -> scoreQueryService.myScore(EXAM_ID)).getMessage());
    }

    @Test
    void myScoreRejectsWhenOwnRecordIsAbsentFromRankedList() {
        loginAs(201L, RoleHierarchy.levelOf(RoleHierarchy.STUDENT));
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_PUBLISHED));
        // 场景不变：本人行不在全班 GRADED 集合里。旧实现靠「selectList 榜单无本人」隐式表达，
        // 名次改聚合后等价显式化为 status 判定——已交卷(2)且有总分仍不属于已汇总集合，一律 404。
        GradingSubmission notGraded = submission(1L, 201L, "14.0", "14.0", 0);
        notGraded.setStatus(ExamSubmission.STATUS_SUBMITTED);
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(notGraded);

        assertEquals("暂无本人成绩记录",
                assertThrows(BusinessException.class, () -> scoreQueryService.myScore(EXAM_ID)).getMessage());
    }

    @Test
    void myScoreReturnsRankAndKeepsPartialFlagWhenNothingIsPending() {
        loginAs(201L, RoleHierarchy.levelOf(RoleHierarchy.STUDENT));
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(EXAM_ID, Exam.STATUS_PUBLISHED));
        GradingSubmission mine = submission(1L, 201L, "14.0", "20.0", 1);
        mine.setStatus(ExamSubmission.STATUS_GRADED);
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(mine);
        // 名次 = 严格更高分人数 + 1（optimize-my-score-rank-fetch）：count=2 ⇒ rank=3，与原期望一致
        when(gradingSubmissionMapper.selectCount(any())).thenReturn(2L);
        when(scoreReviewService.hasPendingReview(EXAM_ID, 201L)).thenReturn(false);

        MyScoreResponse response = scoreQueryService.myScore(EXAM_ID);

        assertFalse(response.getReviewing());
        assertEquals(3, response.getRank());
        assertEquals(0, response.getTotalScore().compareTo(new BigDecimal("20.0")));
        assertEquals(1, response.getPartialGraded());
    }
}
