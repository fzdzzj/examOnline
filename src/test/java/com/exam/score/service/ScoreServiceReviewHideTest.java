package com.exam.score.service;

import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.config.ReadYourWriteMark;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.grading.support.GradingPaperReader;
import com.exam.score.dto.MyScoreResponse;
import com.exam.score.mapper.ScoreAuditLogMapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 学生查成绩处"复核中隐藏"单测（spec score-review §5.4）：
 * 存在进行中的复核申请时，MyScore 不返回分数/排名并置 reviewing=true（前端显示"复核中"，
 * 防"看了分数再申请"）；无进行中复核（含已同意/已驳回后）恢复显示原成绩。
 * 隐藏判定只读 score_review，与成绩发布状态机正交。
 */
@ExtendWith(MockitoExtension.class)
class ScoreServiceReviewHideTest {

    @Mock
    private ExamMapper examMapper;
    @Mock
    private GradingSubmissionMapper gradingSubmissionMapper;
    @Mock
    private SubjectiveGradeMapper subjectiveGradeMapper;
    @Mock
    private GradingPaperReader paperReader;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ScoreAuditLogMapper auditLogMapper;
    @Mock
    private RankCalculator rankCalculator;
    @Mock
    private ReadYourWriteMark readYourWriteMark;
    @Mock
    private ScoreReviewService scoreReviewService;

    @InjectMocks
    private ScoreService scoreService;

    @AfterEach
    void clearSecurity() {
        SecurityUtil.clear();
    }

    private GradingSubmission prepare(int index) {
        GradingSubmission s = new GradingSubmission();
        s.setId(100L + index);
        s.setExamId(10L);
        s.setStudentId(1L);
        s.setObjectiveScore(new BigDecimal("14.0"));
        s.setSubjectiveScore(new BigDecimal("6.0"));
        s.setTotalScore(new BigDecimal("20.0"));
        s.setPartialGraded(0);
        // 名次改聚合后 404 显式判 status：本夹具是「已发布、本人行已汇总」的正例，须显式已批改
        s.setStatus(com.exam.submission.entity.ExamSubmission.STATUS_GRADED);
        return s;
    }

    private void givenPublishedScore() {
        LoginUser user = new LoginUser();
        user.setId(1L);
        user.setRoleLevel(1);
        SecurityUtil.set(user);

        Exam exam = new Exam();
        exam.setId(10L);
        exam.setStatus(Exam.STATUS_PUBLISHED);
        when(examMapper.selectById(10L)).thenReturn(exam);

        GradingSubmission mine = prepare(0);
        when(gradingSubmissionMapper.selectOne(any())).thenReturn(mine);
        // 名次 = 严格更高分人数 + 1（optimize-my-score-rank-fetch）：count=0 ⇒ rank=1，与原期望一致
        when(gradingSubmissionMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void myScoreHidesScoreWhenReviewInProgress() throws Exception {
        givenPublishedScore();
        // 有进行中复核 → 隐藏分数，置 reviewing=true（§5.4 "复核中"）
        when(scoreReviewService.hasPendingReview(10L, 1L)).thenReturn(true);

        MyScoreResponse response = scoreService.myScore(10L);

        assertTrue(Boolean.TRUE.equals(response.getReviewing()));
        assertNull(response.getTotalScore());
        assertNull(response.getObjectiveScore());
        assertNull(response.getSubjectiveScore());
        assertEquals(0, response.getRank());
    }

    @Test
    void myScoreShowsScoreWhenNoReviewInProgress() throws Exception {
        givenPublishedScore();
        // 无进行中复核（含同意/驳回均已结束后）→ 恢复显示原成绩
        when(scoreReviewService.hasPendingReview(10L, 1L)).thenReturn(false);

        MyScoreResponse response = scoreService.myScore(10L);

        assertEquals(false, response.getReviewing());
        assertEquals(0, response.getTotalScore().compareTo(new BigDecimal("20.0")));
        assertEquals(1, response.getRank());
    }
}