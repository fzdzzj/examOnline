package com.exam.score.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.security.OwnershipGuard;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final ScoreReviewService scoreReviewService;
    
    // 指标收集
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public ScoreService(ExamMapper examMapper,
                        GradingSubmissionMapper gradingSubmissionMapper,
                        SubjectiveGradeMapper subjectiveGradeMapper,
                        GradingPaperReader paperReader,
                        UserMapper userMapper,
                        ScoreAuditLogMapper auditLogMapper,
                        RankCalculator rankCalculator,
                        ReadYourWriteMark readYourWriteMark,
                        ScoreReviewService scoreReviewService) {
        this.examMapper = examMapper;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.subjectiveGradeMapper = subjectiveGradeMapper;
        this.paperReader = paperReader;
        this.userMapper = userMapper;
        this.auditLogMapper = auditLogMapper;
        this.rankCalculator = rankCalculator;
        this.readYourWriteMark = readYourWriteMark;
        this.scoreReviewService = scoreReviewService;
    }

    /** 初始化指标收集器（@PostConstruct 确保在发布前完成初始化）。 */
    @Autowired
    public void initMetrics(io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
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

        // 汇总是不可逆的成绩写入：先整场检查判分前置条件，避免前几份已写入后才发现坏答卷。
        // 合法零分仍是已完成判分的结果；主观未批按 §7.5 继续由 computeSummary 标记 partial。
        boolean hasUnfinishedGrading = submissions.stream().anyMatch(submission ->
                submission.getGradingStatus() == null
                        || submission.getGradingStatus() != 1
                        || submission.getObjectiveScore() == null);
        if (hasUnfinishedGrading) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "存在未完成判分的答卷，不能汇总成绩");
        }

        int summarized = 0;
        int skipped = 0;
        // 主观分一次取出（batch-summary-subjective-reads）：进入逐份 CAS 前按本场答卷 ID
        // 一条 IN 查询取回，替代原先每份答卷各查一次的 N+1；缺行答卷仍按未批口径。
        Map<Long, Map<Long, SubjectiveGrade>> gradesBySubmission =
                loadSubjectiveGradesBySubmission(submissions, shortAnswerIds);
        for (GradingSubmission submission : submissions) {
            Summaries summary = computeSummary(submission, shortAnswerIds,
                    gradesBySubmission.getOrDefault(submission.getId(), Map.of()));
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
        log.info("成绩汇总完成：exam={} 汇总={} 跳过={} 考试已批改={}", examId, summarized, skipped, examGraded);
        // 读己之写（add-performance-deepening task4）：成绩汇总是写操作，成功打点，
        // 短窗口内本线程的非强一致读（如判分进度）强制转主库，贴合"刚批改立刻看"
        readYourWriteMark.mark();
        return new SummarizeStats(summarized, skipped, examGraded);
    }

    /**
     * 汇总前一次取出本场全部主观分（batch-summary-subjective-reads）：按答卷 ID 分组、
     * 组内按题 ID 归一（同题多行取首行，脏数据不中断汇总）。无简答题或无答卷时不查询。
     */
    private Map<Long, Map<Long, SubjectiveGrade>> loadSubjectiveGradesBySubmission(
            List<GradingSubmission> submissions, List<Long> shortAnswerIds) {
        if (shortAnswerIds.isEmpty() || submissions.isEmpty()) {
            return Map.of();
        }
        List<SubjectiveGrade> rows = subjectiveGradeMapper.selectList(
                Wrappers.<SubjectiveGrade>lambdaQuery()
                        .in(SubjectiveGrade::getSubmissionId,
                                submissions.stream().map(GradingSubmission::getId).toList()));
        return rows.stream().collect(Collectors.groupingBy(SubjectiveGrade::getSubmissionId,
                Collectors.toMap(SubjectiveGrade::getQuestionId, Function.identity(), (a, b) -> a)));
    }

    /** 单份答卷汇总值：总分 = 客观 (未判按 0) + 主观 (已批之和)；部分批改以卷面简答题全集判定。 */
    private record Summaries(BigDecimal subjectiveScore, BigDecimal totalScore, boolean partialGraded) {
    }

    private Summaries computeSummary(GradingSubmission submission, List<Long> shortAnswerIds,
                                     Map<Long, SubjectiveGrade> gradesByQuestion) {
        BigDecimal objective = submission.getObjectiveScore() == null
                ? BigDecimal.ZERO : submission.getObjectiveScore();

        BigDecimal subjective = BigDecimal.ZERO;
        boolean partial = false;
        for (Long questionId : shortAnswerIds) {
            SubjectiveGrade row = gradesByQuestion.get(questionId);
            if (row == null || row.getScore() == null) {
                // 缺行或未批：按 0 分计入总分并标记部分批改（§7.5）
                partial = true;
            } else {
                subjective = subjective.add(row.getScore());
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

    // ==================== 发布 / 撤回 ====================

    /**
     * 批量发布（spec「批量发布」场景）：已批改→已发布，学生端立即可见。
     * 单场业务性失败不影响其余（部分成功语义），逐场返回结果；每场成功均落 PUBLISH 审计。
     *
     * <p><b>整批共用本方法这一个事务</b>，这是有意的：基础设施故障（非 BusinessException）
     * 会一路抛出、触发整体回滚，不会出现"一半考试已发布、一半没发"的静默不一致；
     * 而业务性失败在 {@link #publishOne} 内被转成逐场结果。之所以能这么合并，是因为
     * publishOne 里的 BusinessException 只可能在**尚未产生任何写操作**时抛出
     * （状态前置校验、CAS 抢输即 0 行），所以"捕获后不回滚"不会留下该场的半截写入。
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

    /**
     * 单场发布 + 打点。
     *
     * <p>这里<b>不加</b> {@code @Transactional(REQUIRES_NEW)}：本方法是 private 且由同类
     * publish() 直接调用，Spring 基于代理的事务增强在两种情形下都不生效（私有方法不被代理、
     * 自调用绕过代理），写上只会让读代码的人误以为存在"每场独立事务"。
     * 真实边界见 {@link #publish} 的注释。
     *
     * <p>指标<b>不按 exam_id 打 tag</b>：考试 ID 基数无上界，逐考试生成时间序列会让
     * Prometheus 的时间序列数量随业务量线性膨胀（内存与查询成本失控）；按 status 聚合
     * 已足够回答"发布成功率与耗时分布"，具体是哪场失败看返回结果与审计表。
     */
    private ScoreActionItem publishOne(Long examId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            ScoreActionItem result = doPublishOne(examId);
            recordPublish(sample, "success");
            return result;
        } catch (BusinessException e) {
            recordPublish(sample, "fail");
            throw e;
        } catch (Exception e) {
            recordPublish(sample, "error");
            log.error("成绩发布异常：exam={}", examId, e);
            throw e;
        }
    }

    /** 收尾一次发布打点：耗时直方图 + 按结果分类的计数，tag 只有 status。 */
    private void recordPublish(Timer.Sample sample, String status) {
        sample.stop(Timer.builder("exam_publish_duration_seconds")
                .description("考试发布耗时分布")
                .tag("status", status)
                .register(meterRegistry));
        Counter.builder("exam_publish_total")
                .description("考试发布次数，按结果分类")
                .tag("status", status)
                .register(meterRegistry).increment();
    }

    private ScoreActionItem doPublishOne(Long examId) {
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
        log.info("成绩发布：exam={} 操作人={}", examId, SecurityUtil.getUserId());
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
        log.info("成绩撤回：exam={} 操作人={} 原因={}", examId, SecurityUtil.getUserId(), reason);
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
