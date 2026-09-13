package com.exam.score.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.OwnershipGuard;
import com.exam.auth.security.RequireRole;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.ReadYourWriteMark;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.grading.model.GradingPaper;
import com.exam.grading.model.GradingQuestion;
import com.exam.grading.support.GradingPaperReader;
import com.exam.score.dto.MyScoreResponse;
import com.exam.score.dto.ScoreActionItem;
import com.exam.score.dto.ScoreItem;
import com.exam.score.dto.ScorePreviewResponse;
import com.exam.score.entity.ScoreAuditLog;
import com.exam.score.mapper.ScoreAuditLogMapper;
import com.exam.submission.entity.ExamSubmission;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 成绩汇总与发布撤回服务（spec score-management，docs/需求决策记录.md §5.3/§7.5/§11.6）：
 *
 * <ul>
 *   <li>汇总：总分 = 客观 + 主观（未批简答按 0 分并打"部分批改"标记，§7.5），
 *       答卷 2/3→3 走 CAS（GradingSubmissionMapper.casSummarize），考试 2→3 走状态机 CAS；</li>
 *   <li>发布：已批改→已发布，支持批量；发布前必须先预览（预览即含排名）；</li>
 *   <li>撤回：仅管理员（§5.3），已发布→已批改，学生端立即不可见，原因必填 + 审计留痕；</li>
 *   <li>发布/撤回全部落 score_audit_logs（谁/何时/做了什么/原因），append-only。</li>
 * </ul>
 *
 * <p>事务统一显式 rollbackFor=Exception.class（见 data-consistency 规范），防未来受检异常静默不回滚。
 */
@Slf4j
@Service
public class ScoreService {

    private final ExamMapper examMapper;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final SubjectiveGradeMapper subjectiveGradeMapper;
    private final GradingPaperReader paperReader;
    private final UserMapper userMapper;
    private final ScoreAuditLogMapper auditLogMapper;
    private final RankCalculator rankCalculator;
    private final ReadYourWriteMark readYourWriteMark;

    public ScoreService(ExamMapper examMapper,
                        GradingSubmissionMapper gradingSubmissionMapper,
                        SubjectiveGradeMapper subjectiveGradeMapper,
                        GradingPaperReader paperReader,
                        UserMapper userMapper,
                        ScoreAuditLogMapper auditLogMapper,
                        RankCalculator rankCalculator,
                        ReadYourWriteMark readYourWriteMark) {
        this.examMapper = examMapper;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.subjectiveGradeMapper = subjectiveGradeMapper;
        this.paperReader = paperReader;
        this.userMapper = userMapper;
        this.auditLogMapper = auditLogMapper;
        this.rankCalculator = rankCalculator;
        this.readYourWriteMark = readYourWriteMark;
    }

    // ==================== 汇总 ====================

    /** 汇总统计。 */
    public record SummarizeStats(int summarized, int skipped, boolean examGraded) {
    }

    /**
     * 成绩汇总（spec「成绩汇总」场景；重判/补批后可重复执行刷新总分）：
     * 逐份答卷 CAS 写入 subjective_score/total_score/partial_graded 并迁至"已批改"，
     * 全部处理完后再把考试状态推到"已批改"（乐观锁 CAS，并发汇总仅一次生效）。
     */
    @Transactional(rollbackFor = Exception.class)
    public SummarizeStats summarize(Long examId) {
        Exam exam = requireOwnedExam(examId);
        if (exam.getStatus() == Exam.STATUS_PUBLISHED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "成绩已发布，须先撤回再重新汇总");
        }
        if (exam.getStatus() < Exam.STATUS_ENDED) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "考试尚未结束，不能汇总成绩");
        }
        // 快照提供简答题全集：判定"部分批改"必须以卷面结构为准（缺行 = 判分未覆盖，同样算未批）
        GradingPaper paper = paperReader.readByExamId(examId);
        List<Long> shortAnswerIds = paper.shortAnswerQuestions().stream()
                .map(GradingQuestion::questionId).toList();

        List<GradingSubmission> submissions = gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .in(GradingSubmission::getStatus,
                                ExamSubmission.STATUS_SUBMITTED, ExamSubmission.STATUS_GRADED));

        int summarized = 0;
        int skipped = 0;
        for (GradingSubmission submission : submissions) {
            Summaries summary = computeSummary(submission, shortAnswerIds);
            // 答卷级 CAS：并发汇总/并发批改保存时仅一个写入生效，总分不被交叉覆盖
            if (gradingSubmissionMapper.casSummarize(submission.getId(),
                    summary.subjectiveScore(), summary.totalScore(), summary.partialGraded() ? 1 : 0) > 0) {
                summarized++;
            } else {
                skipped++;
            }
        }

        // 考试状态已结束→已批改（已批改则保持），CAS 0 行视为并发已迁移，静默通过
        boolean examGraded = false;
        Exam latest = examMapper.selectById(examId);
        if (latest.getStatus() == Exam.STATUS_ENDED) {
            examGraded = examMapper.casUpdateStatus(examId, Exam.STATUS_ENDED,
                    Exam.STATUS_GRADED, latest.getVersion()) > 0;
        } else {
            examGraded = latest.getStatus() == Exam.STATUS_GRADED;
        }
        log.info("成绩汇总完成: exam={} 汇总={} 跳过={} 考试已批改={}", examId, summarized, skipped, examGraded);
        // 读己之写（add-performance-deepening task4）：成绩汇总是写操作，成功打点，
        // 短窗口内本线程的非强一致读（如判分进度）强制转主库，贴合"刚批改立刻看"
        readYourWriteMark.mark();
        return new SummarizeStats(summarized, skipped, examGraded);
    }

    /** 单份答卷汇总值：总分 = 客观(未判按0) + 主观(已批之和)；部分批改以卷面简答题全集判定。 */
    private record Summaries(BigDecimal subjectiveScore, BigDecimal totalScore, boolean partialGraded) {
    }

    private Summaries computeSummary(GradingSubmission submission, List<Long> shortAnswerIds) {
        BigDecimal objective = submission.getObjectiveScore() == null
                ? BigDecimal.ZERO : submission.getObjectiveScore();

        BigDecimal subjective = BigDecimal.ZERO;
        boolean partial = false;
        if (!shortAnswerIds.isEmpty()) {
            List<SubjectiveGrade> rows = subjectiveGradeMapper.selectList(
                    Wrappers.<SubjectiveGrade>lambdaQuery()
                            .eq(SubjectiveGrade::getSubmissionId, submission.getId()));
            Map<Long, SubjectiveGrade> byQuestion = rows.stream()
                    .collect(Collectors.toMap(SubjectiveGrade::getQuestionId, Function.identity(),
                            (a, b) -> a));
            for (Long questionId : shortAnswerIds) {
                SubjectiveGrade row = byQuestion.get(questionId);
                if (row == null || row.getScore() == null) {
                    // 缺行或未批：按 0 分计入总分并标记部分批改（§7.5）
                    partial = true;
                } else {
                    subjective = subjective.add(row.getScore());
                }
            }
        }
        return new Summaries(subjective, objective.add(subjective), partial);
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
        if (submission == null || submission.getTotalScore() == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "暂无本人成绩记录");
        }

        // 排名在全班已汇总成绩中计算（与预览/导出同一口径）；按答卷定位本人下标，避免同分 equals 歧义
        List<GradingSubmission> gradedAll = gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED)
                        .isNotNull(GradingSubmission::getTotalScore));
        int myIndex = -1;
        for (int i = 0; i < gradedAll.size(); i++) {
            if (gradedAll.get(i).getId().equals(submission.getId())) {
                myIndex = i;
                break;
            }
        }
        if (myIndex < 0) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "暂无本人成绩记录");
        }
        int rank = rankCalculator.rank(gradedAll.stream()
                .map(GradingSubmission::getTotalScore).toList())[myIndex];

        MyScoreResponse response = new MyScoreResponse();
        response.setExamId(examId);
        response.setExamTitle(exam.getTitle());
        response.setObjectiveScore(submission.getObjectiveScore());
        response.setSubjectiveScore(submission.getSubjectiveScore());
        response.setTotalScore(submission.getTotalScore());
        response.setRank(rank);
        response.setPartialGraded(submission.getPartialGraded());
        return response;
    }

    // ==================== 发布 / 撤回 ====================

    /**
     * 批量发布（spec「批量发布」场景）：已批改→已发布，学生端立即可见。
     * 单场失败不影响其余（部分成功语义），逐场返回结果；每场成功均落 PUBLISH 审计。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<ScoreActionItem> publish(List<Long> examIds) {
        List<ScoreActionItem> results = new ArrayList<>(examIds.size());
        for (Long examId : examIds) {
            try {
                results.add(publishOne(examId));
            } catch (BusinessException e) {
                results.add(new ScoreActionItem(examId, false, e.getMessage()));
            }
        }
        return results;
    }

    private ScoreActionItem publishOne(Long examId) {
        Exam exam = requireOwnedExam(examId);
        if (exam.getStatus() == Exam.STATUS_PUBLISHED) {
            return new ScoreActionItem(examId, true, "已发布（幂等跳过）");
        }
        if (exam.getStatus() != Exam.STATUS_GRADED) {
            return new ScoreActionItem(examId, false, "考试未完成批改，不能发布");
        }
        int rows = examMapper.casUpdateStatus(examId, Exam.STATUS_GRADED,
                Exam.STATUS_PUBLISHED, exam.getVersion());
        if (rows == 0) {
            throw new BusinessException(ResponseCode.STATE_CONFLICT, "考试状态已变化，请刷新后重试");
        }
        Long summarized = gradingSubmissionMapper.selectCount(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .eq(GradingSubmission::getStatus, ExamSubmission.STATUS_GRADED));
        writeAudit(examId, ScoreAuditLog.ACTION_PUBLISH, null, "发布成绩 " + summarized + " 人");
        log.info("成绩发布: exam={} 操作人={}", examId, SecurityUtil.getUserId());
        // 读己之写（add-performance-deepening task4）：发布成绩是写操作，成功打点——
        // 学生/教师随即查成绩是强一致读（本就走主库），此处标记同时兜底同线程其他非强一致读
        readYourWriteMark.mark();
        return new ScoreActionItem(examId, true, "发布成功");
    }

    /**
     * 批量撤回（spec「成绩撤回」场景）：仅管理员；已发布→已批改，
     * 学生端立即不可见（显示"成绩待发布"）；原因必填，逐场落 REVOKE 审计。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<ScoreActionItem> revoke(List<Long> examIds, String reason) {
        // 双重校验：注解 @RequireRole 拦截层 + Service 内断言（防绕过直调）
        LoginRoleCheck.assertAdmin();
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "撤回必须填写原因（审计要求）");
        }
        List<ScoreActionItem> results = new ArrayList<>(examIds.size());
        for (Long examId : examIds) {
            try {
                results.add(revokeOne(examId, reason));
            } catch (BusinessException e) {
                results.add(new ScoreActionItem(examId, false, e.getMessage()));
            }
        }
        return results;
    }

    private ScoreActionItem revokeOne(Long examId, String reason) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            return new ScoreActionItem(examId, false, "考试不存在");
        }
        // 撤回是全局管理动作：不要求操作者是考试归属教师，但必须 ADMIN（§5.3）
        if (exam.getStatus() != Exam.STATUS_PUBLISHED) {
            return new ScoreActionItem(examId, false, "考试成绩未处于已发布状态");
        }
        int rows = examMapper.casUpdateStatus(examId, Exam.STATUS_PUBLISHED,
                Exam.STATUS_GRADED, exam.getVersion());
        if (rows == 0) {
            throw new BusinessException(ResponseCode.STATE_CONFLICT, "考试状态已变化，请刷新后重试");
        }
        writeAudit(examId, ScoreAuditLog.ACTION_REVOKE, reason, "撤回成绩，学生端隐藏");
        log.info("成绩撤回: exam={} 操作人={} 原因={}", examId, SecurityUtil.getUserId(), reason);
        // 读己之写（add-performance-deepening task4）：撤回是状态写，成功打点
        readYourWriteMark.mark();
        return new ScoreActionItem(examId, true, "撤回成功");
    }

    // ==================== 私有工具 ====================

    private void writeAudit(Long examId, String action, String reason, String detail) {
        ScoreAuditLog audit = new ScoreAuditLog();
        audit.setExamId(examId);
        audit.setAction(action);
        audit.setOperatorId(SecurityUtil.getUserId());
        audit.setReason(reason);
        audit.setDetail(detail);
        auditLogMapper.insert(audit);
    }

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

    /** Service 内管理员断言（与 @RequireRole 注解构成双保险）。 */
    private static final class LoginRoleCheck {
        static void assertAdmin() {
            com.exam.auth.security.LoginUser operator = SecurityUtil.getCurrentUser();
            if (operator == null) {
                throw new BusinessException(ResponseCode.TOKEN_INVALID);
            }
            if (operator.getRoleLevel() < RoleHierarchy.levelOf(RoleHierarchy.ADMIN)) {
                throw new BusinessException(ResponseCode.FORBIDDEN, "成绩撤回仅管理员可执行");
            }
        }
    }
}
