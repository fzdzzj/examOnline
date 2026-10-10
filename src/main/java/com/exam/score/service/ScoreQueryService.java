package com.exam.score.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.OwnershipGuard;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.score.dto.MyScoreResponse;
import com.exam.score.dto.ScoreItem;
import com.exam.score.dto.ScorePreviewResponse;
import com.exam.submission.entity.ExamSubmission;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 成绩读侧服务（spec score-management「发布前预览」「学生仅见自己成绩」）：
 * 发布前预览与学生查分两个强一致读自 ScoreService 读写分置拆出（split-score-query-service），
 * 方法体逐字迁入、事务语义零改动——两方法均无事务、默认主库（不加 {@code @DS}，反射护栏见
 * MyScoreRankEquivalenceTest e8b），与写侧（ScoreService 汇总/发布/撤回）互不共享事务上下文。
 */
@Service
public class ScoreQueryService {

    private final ExamMapper examMapper;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final UserMapper userMapper;
    private final RankCalculator rankCalculator;
    private final ScoreReviewService scoreReviewService;

    public ScoreQueryService(ExamMapper examMapper,
                             GradingSubmissionMapper gradingSubmissionMapper,
                             UserMapper userMapper,
                             RankCalculator rankCalculator,
                             ScoreReviewService scoreReviewService) {
        this.examMapper = examMapper;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.userMapper = userMapper;
        this.rankCalculator = rankCalculator;
        this.scoreReviewService = scoreReviewService;
    }

    // ==================== 预览 / 查询 ====================

    /**
     * 发布前预览（spec「发布前预览」场景）：各学生成绩明细 + 排名（并列同名次）。
     * 已发布状态下同样可查（发布后教师复核当前榜单）。
     */
    public ScorePreviewResponse publishPreview(Long examId) {
        Exam exam = requireOwnedExam(examId);
        if (exam.getStatus() < Exam.STATUS_GRADED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "成绩尚未汇总，请先执行汇总");
        }

        List<GradingSubmission> submissions = gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        // M5 列投影（冻结列集见 evidence/PREREGISTRATION.md §0）：预览只消费这 5 个标量字段，
                        // 不再载入 answers 长字段。裁决见 evidence/adjudication.json（本卡唯一 GO 单元）。
                        .select(GradingSubmission::getStudentId, GradingSubmission::getObjectiveScore,
                                GradingSubmission::getSubjectiveScore, GradingSubmission::getTotalScore,
                                GradingSubmission::getPartialGraded)
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                        .isNotNull(GradingSubmission::getTotalScore)
                        .orderByDesc(GradingSubmission::getTotalScore));

        Map<Long, User> users = loadUsers(submissions.stream()
                .map(GradingSubmission::getStudentId).toList());
        int[] ranks = rankCalculator.rank(submissions.stream()
                .map(GradingSubmission::getTotalScore).toList());

        ScorePreviewResponse response = new ScorePreviewResponse();
        response.setExamId(examId);
        response.setExamTitle(exam.getTitle());
        response.setSummarizedCount(submissions.size());
        List<ScoreItem> items = new ArrayList<>(submissions.size());
        int partialCount = 0;
        for (int i = 0; i < submissions.size(); i++) {
            GradingSubmission submission = submissions.get(i);
            ScoreItem item = new ScoreItem();
            item.setStudentId(submission.getStudentId());
            User user = users.get(submission.getStudentId());
            item.setStudentName(user == null ? "未知学生" : user.getName());
            item.setObjectiveScore(submission.getObjectiveScore());
            item.setSubjectiveScore(submission.getSubjectiveScore());
            item.setTotalScore(submission.getTotalScore());
            item.setRank(ranks[i]);
            item.setPartialGraded(submission.getPartialGraded());
            if (submission.getPartialGraded() != null && submission.getPartialGraded() == 1) {
                partialCount++;
            }
            items.add(item);
        }
        response.setPartialGradedCount(partialCount);
        response.setItems(items);
        return response;
    }

    /**
     * 学生查自己成绩（spec「学生仅见自己成绩」）：成绩未发布时一律拒绝，不泄露任何分数。
     *
     * <p>读写分离（add-performance-deepening task3）：成绩查询是<b>强一致读</b>——
     * 发布/批改后立即查必须是最新分数，主从延迟会查到旧分/查不到，因此<b>不加
     * {@code @DS("slave")}</b>、默认走主库（primary=master）。
     */
    public MyScoreResponse myScore(Long examId) {
        Long studentId = SecurityUtil.getUserId();
        if (studentId == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        if (exam.getStatus() != Exam.STATUS_PUBLISHED) {
            // 未发布/已撤回统一口径"成绩待发布"（§5.3），不区分阶段避免泄露批改进度
            throw new BusinessException(ResponseCode.BAD_REQUEST, "成绩待发布");
        }
        GradingSubmission submission = gradingSubmissionMapper.selectOne(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStudentId, studentId));
        // 404 判定显式化（optimize-my-score-rank-fetch）：无行 / 总分未产生 / 状态不是已批改，
        // 与旧实现「本人行落在全班 GRADED 且总分非空集合」的隐式判定逐条等价。
        if (submission == null || submission.getTotalScore() == null
                || !Objects.equals(submission.getStatus(), ExamSubmission.STATUS_GRADED)) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "暂无本人成绩记录");
        }

        // 名次 = 全班已汇总答卷中总分严格更高的人数 + 1（竞赛排名：同分并列、后续跳号，
        // 与预览/导出的 RankCalculator 口径一致）；一条聚合计数，不再取回全班答卷行——
        // 同负载归因（optimize-my-score-rank-fetch 阶段 2）证实「取数+结果映射」为该请求
        // 占比最高的一类，行级取数自此不随班级人数增长。
        Long higherCount = gradingSubmissionMapper.selectCount(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                        .isNotNull(GradingSubmission::getTotalScore)
                        .gt(GradingSubmission::getTotalScore, submission.getTotalScore()));
        int rank = higherCount.intValue() + 1;

        MyScoreResponse response = new MyScoreResponse();
        response.setExamId(examId);
        response.setExamTitle(exam.getTitle());

        // 复核中隐藏成绩（§5.4，spec score-review）：学生存在进行中的复核申请时，
        // 不返回分数，置 reviewing=true 供前端显示"复核中"——防止学生先看分数再申请复核。
        // 复核状态与成绩发布状态机正交：只读"是否有进行中复核申请"决定显示与否，不改动发布状态。
        if (scoreReviewService.hasPendingReview(examId, studentId)) {
            response.setReviewing(true);
            response.setObjectiveScore(null);
            response.setSubjectiveScore(null);
            response.setTotalScore(null);
            response.setRank(0);
            return response;
        }

        response.setReviewing(false);
        response.setObjectiveScore(submission.getObjectiveScore());
        response.setSubjectiveScore(submission.getSubjectiveScore());
        response.setTotalScore(submission.getTotalScore());
        response.setRank(rank);
        response.setPartialGraded(submission.getPartialGraded());
        return response;
    }

    // ==================== 私有工具 ====================

    private Exam requireOwnedExam(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        }
        OwnershipGuard.assertOwner(exam.getCreatedBy(), SecurityUtil.getCurrentUser(), "考试");
        return exam;
    }

    private Map<Long, User> loadUsers(List<Long> studentIds) {
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(studentIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }
}
