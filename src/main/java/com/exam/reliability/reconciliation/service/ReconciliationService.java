package com.exam.reliability.reconciliation.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.service.ObjectiveGradingService;
import com.exam.reliability.reconciliation.dto.ReconciliationDiffResponse;
import com.exam.reliability.reconciliation.dto.ReconciliationRunResponse;
import com.exam.reliability.reconciliation.entity.ReconciliationDiff;
import com.exam.reliability.reconciliation.entity.ReconciliationRun;
import com.exam.reliability.reconciliation.mapper.ReconciliationDiffMapper;
import com.exam.reliability.reconciliation.mapper.ReconciliationRunMapper;
import com.exam.score.service.ScoreService;
import com.exam.service.AuditLogService;
import com.exam.submission.entity.ExamSubmission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 成绩链全链路对账引擎（spec reliability「成绩链全链路对账」，add-score-chain-reconciliation）：
 *
 * <p><b>两个维度</b>：
 * <ol>
 *   <li>交卷 vs 判分——已判分考试（status=3/4）下的已交卷答卷，判分记录缺失（grading_status≠1
 *       或客观分未回填）与主观题未批完成 {@link ReconciliationDiff#TYPE_SUBMISSION_UNGRADED} 票；
 *       其中「判分完成但总分未回填」是<b>确定性缺失</b>，按同源汇总口径幂等补齐并记审计；</li>
 *   <li>判分明细 vs 总分——用与 {@link ScoreService#summarize} 完全相同的
 *       {@link ScoreService#expectedSummaries} 口径计算期望总分，与库中 total_score 逐答卷比对，
 *       不一致出 {@link ReconciliationDiff#TYPE_DETAIL_TOTAL_MISMATCH} 票（期望值/实际值/偏差），
 *       <b>只出票不自动改分</b>。</li>
 * </ol>
 *
 * <p><b>判分口径同源</b>：期望总分不在这里另写公式，而是复用 ScoreService 的
 * computeSummary（客观分 + 已批主观之和，未批按 0 并打部分批改标记，§7.5）；
 * 补齐写入也复用同一 SET 形状的 CAS（仅多一个 total_score IS NULL 幂等护栏）。
 *
 * <p><b>多实例安全</b>（reliability 域「定时任务的并发与多实例安全」既有口径）：
 * 定时批次以 {@code batchKey = "SCHEDULED:" + 时间窗序号} 抢占 {@code uk_recon_run_batch}，
 * 同一时间窗只有一格能插入，其余实例收到重复键即跳过——不加分布式调度锁、零新 Redis 键族，
 * 正确性由 DB 唯一索引与条件更新保证。失败只写进运行记录（error_message + FAILED），
 * 不写任何全局状态，下一次时间窗照常执行。
 *
 * <p><b>零新 Redis 键族</b>：对账状态全部落 DB，本类不触碰 Redis。
 */
@Slf4j
@Service
public class ReconciliationService {

    /** 单页上限：控制器 @Max 拒绝越界，这里再夹紧一次（与 AuditLogService 同口径）。 */
    static final long MAX_PAGE_SIZE = 100;

    private final ReconciliationRunMapper runMapper;
    private final ReconciliationDiffMapper diffMapper;
    private final ExamMapper examMapper;
    private final GradingSubmissionMapper gradingSubmissionMapper;
    private final ScoreService scoreService;
    private final AuditLogService auditLogService;

    /** 单次运行最多扫描的考试数（有界，避免定时兜底一次扫全库）。 */
    @Value("${exam.reconciliation.max-exams-per-run:50}")
    private int maxExamsPerRun;

    /** 定时兜底开关：true 才由 @Scheduled 驱动；手动触发不受此开关影响。 */
    @Value("${exam.reconciliation.scheduled.enabled:true}")
    private boolean scheduledEnabled;

    /**
     * 定时批次时间窗（秒）：同一窗口内的多实例触发视为同一批次，只有一格落运行记录。
     * 缺省 10 分钟与固定间隔同量级，窗口过小会退化为「各实例各跑一遍」。
     */
    @Value("${exam.reconciliation.scheduled.interval-seconds:600}")
    private long scheduledIntervalSeconds;

    public ReconciliationService(ReconciliationRunMapper runMapper,
                                 ReconciliationDiffMapper diffMapper,
                                 ExamMapper examMapper,
                                 GradingSubmissionMapper gradingSubmissionMapper,
                                 ScoreService scoreService,
                                 AuditLogService auditLogService) {
        this.runMapper = runMapper;
        this.diffMapper = diffMapper;
        this.examMapper = examMapper;
        this.gradingSubmissionMapper = gradingSubmissionMapper;
        this.scoreService = scoreService;
        this.auditLogService = auditLogService;
    }

    // ==================== 触发入口 ====================

    /**
     * 手动触发对账（POST /api/admin/reconciliation/run）。
     * examId 为空表示按范围扫描全部已判分考试（有界）；显式指定考试时先校验其已达已判分状态。
     */
    public ReconciliationRun runManual(Long examId, Long operatorId) {
        List<Long> examIds;
        if (examId != null) {
            Exam exam = examMapper.selectById(examId);
            if (exam == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
            }
            if (exam.getStatus() == null
                    || exam.getStatus() < Exam.STATUS_GRADED
                    || exam.getStatus() > Exam.STATUS_PUBLISHED) {
                throw new BusinessException(ResponseCode.BAD_REQUEST, "考试未达已判分状态，无法对账");
            }
            examIds = List.of(examId);
        } else {
            examIds = candidateExamIds();
        }
        ReconciliationRun run = newRun(ReconciliationRun.TRIGGER_MANUAL, examId, null, operatorId);
        runMapper.insert(run);
        return execute(run, examIds);
    }

    /**
     * 定时兜底一轮（业务入口与 {@link #scheduledTick()} 分离，便于确定性测试）：
     * 以 {@code now} 派生批次键并抢占唯一索引；同一窗口已被抢到则返回 empty（不重复执行）。
     *
     * <p>本方法不判 {@code scheduled.enabled}——开关只决定 @Scheduled 是否驱动它，
     * 手动/测试直调批次语义时开关不应改变「同窗口只执行一次」的行为。
     *
     * @param now 批次时钟（生产传 LocalDateTime.now()；测试可传固定时刻复现并发）
     */
    public Optional<ReconciliationRun> runScheduled(LocalDateTime now) {
        String batchKey = batchKeyOf(now);
        ReconciliationRun run = newRun(ReconciliationRun.TRIGGER_SCHEDULED, null, batchKey, null);
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException e) {
            // 同一时间窗已被本实例上一轮或其他实例抢占：不做任何业务，直接跳过。
            log.warn("定时对账批次已执行，跳过本轮: batchKey={}", batchKey);
            return Optional.empty();
        }
        return Optional.of(execute(run, candidateExamIds()));
    }

    /**
     * @Scheduled 定时入口：只负责取时钟，业务在 {@link #runScheduled(LocalDateTime)}。
     * 异常在此吞掉并打 ERROR——定时任务异常不得影响后续触发（失败原因已入运行记录）。
     */
    @Scheduled(fixedDelayString = "${exam.reconciliation.scheduled.fixed-delay-ms:600000}",
            initialDelayString = "${exam.reconciliation.scheduled.initial-delay-ms:60000}")
    public void scheduledTick() {
        if (!scheduledEnabled) {
            return;
        }
        try {
            runScheduled(LocalDateTime.now());
        } catch (Exception e) {
            log.error("定时成绩链对账失败（下一轮照常执行）", e);
        }
    }

    // ==================== 查询与闭环 ====================

    /** 运行历史分页（id 倒序=最近优先）；可按考试范围筛选。 */
    public List<ReconciliationRunResponse> pageRuns(long page, long size, Long examId) {
        Page<ReconciliationRun> raw = runMapper.selectPage(
                new Page<>(Math.max(page, 1), clampSize(size)),
                Wrappers.<ReconciliationRun>lambdaQuery()
                        .eq(examId != null, ReconciliationRun::getExamId, examId)
                        .orderByDesc(ReconciliationRun::getId));
        return raw.getRecords().stream().map(ReconciliationRunResponse::from).toList();
    }

    /** 差异票分页（id 倒序）；可按 run/类型/状态筛选，筛选为空则不携带该条件。 */
    public List<ReconciliationDiffResponse> pageDiffs(long page, long size, Long runId,
                                                      String diffType, String status) {
        Page<ReconciliationDiff> raw = diffMapper.selectPage(
                new Page<>(Math.max(page, 1), clampSize(size)),
                Wrappers.<ReconciliationDiff>lambdaQuery()
                        .eq(runId != null, ReconciliationDiff::getRunId, runId)
                        .eq(diffType != null && !diffType.isBlank(),
                                ReconciliationDiff::getDiffType, diffType)
                        .eq(status != null && !status.isBlank(), ReconciliationDiff::getStatus, status)
                        .orderByDesc(ReconciliationDiff::getId));
        return raw.getRecords().stream().map(ReconciliationDiffResponse::from).toList();
    }

    /**
     * 人工关闭差异票：仅 OPEN 可关闭，按 (id, status=OPEN) 条件更新（并发下只一次生效），
     * 关闭动作落 audit_log（操作人取自 SecurityUtil）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReconciliationDiffResponse closeDiff(Long diffId, String reason) {
        ReconciliationDiff diff = diffMapper.selectById(diffId);
        if (diff == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "差异票不存在");
        }
        if (!ReconciliationDiff.STATUS_OPEN.equals(diff.getStatus())) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "仅 OPEN 状态差异票可人工关闭");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = diffMapper.update(null, Wrappers.<ReconciliationDiff>lambdaUpdate()
                .eq(ReconciliationDiff::getId, diffId)
                .eq(ReconciliationDiff::getStatus, ReconciliationDiff.STATUS_OPEN)
                .set(ReconciliationDiff::getStatus, ReconciliationDiff.STATUS_MANUAL_CLOSED)
                .set(ReconciliationDiff::getClosedBy, SecurityUtil.getUserId())
                .set(ReconciliationDiff::getClosedTime, now)
                .set(ReconciliationDiff::getUpdatedTime, now));
        if (rows == 0) {
            throw new BusinessException(ResponseCode.STATE_CONFLICT, "差异票状态已变化，请刷新后重试");
        }
        auditLogService.logScoreReconcileDiffClose(diffId, diff.getDiffType(),
                diff.getExamId(), diff.getSubmissionId(), reason);
        log.info("差异票人工关闭: diff={} type={} exam={} submission={} 操作人={}",
                diffId, diff.getDiffType(), diff.getExamId(), diff.getSubmissionId(),
                SecurityUtil.getUserId());
        return ReconciliationDiffResponse.from(diffMapper.selectById(diffId));
    }

    // ==================== 对账执行 ====================

    /** 一次运行覆盖的考试集合处理：逐考试隔离失败（单场快照损坏不中断整批），收尾写运行记录。 */
    private ReconciliationRun execute(ReconciliationRun run, List<Long> examIds) {
        Totals totals = new Totals();
        List<String> errors = new ArrayList<>();
        for (Long examId : examIds) {
            try {
                reconcileExam(run.getId(), examId, totals);
            } catch (Exception e) {
                errors.add("exam=" + examId + ": " + e.getMessage());
                log.error("成绩链对账失败 exam={} run={}", examId, run.getId(), e);
            }
        }
        boolean failed = !errors.isEmpty();
        finish(run, failed ? ReconciliationRun.STATUS_FAILED : ReconciliationRun.STATUS_SUCCESS,
                totals, failed ? String.join("; ", errors) : null);
        log.info("成绩链对账完成: run={} trigger={} exams={} 扫描={} 未判分={} 明细总分不一致={} 自动补齐={}",
                run.getId(), run.getTriggerSource(), examIds.size(), totals.scanned,
                totals.ungraded, totals.mismatch, totals.autoFixed);
        return run;
    }

    /** 单场对账：两个维度逐答卷比对，所有差异出票（重复差异由唯一指纹抑制）。 */
    private void reconcileExam(Long runId, Long examId, Totals totals) {
        List<GradingSubmission> submissions = gradingSubmissionMapper.selectList(
                Wrappers.<GradingSubmission>lambdaQuery()
                        .eq(GradingSubmission::getExamId, examId)
                        .in(GradingSubmission::getStatus,
                                ExamSubmission.STATUS_SUBMITTED, ExamSubmission.STATUS_GRADED));
        if (submissions == null || submissions.isEmpty()) {
            return;
        }
        // 同源口径：快照读一次、主观分一次批量取出，逐份仍走 ScoreService.computeSummary
        Map<Long, ScoreService.ExpectedSummary> expected =
                scoreService.expectedSummaries(examId, submissions);

        for (GradingSubmission submission : submissions) {
            totals.scanned++;
            Integer gradingStatus = submission.getGradingStatus();
            if (gradingStatus == null
                    || gradingStatus != ObjectiveGradingService.GRADING_OK
                    || submission.getObjectiveScore() == null) {
                // 维度一：交卷了但判分记录缺失 → 只出票，待人工重判（不自动改分）
                totals.ungraded++;
                insertTicket(runId, submission, ReconciliationDiff.TYPE_SUBMISSION_UNGRADED,
                        ReconciliationDiff.DETAIL_GRADING_MISSING, ReconciliationDiff.STATUS_OPEN,
                        null, null, null);
                continue;
            }

            ScoreService.ExpectedSummary summary = expected.get(submission.getId());
            if (summary == null) {
                continue;
            }
            if (summary.partialGraded()) {
                // 维度一：主观题未批完（考试已成已判分态）→ 只出票，待人工补批
                totals.ungraded++;
                insertTicket(runId, submission, ReconciliationDiff.TYPE_SUBMISSION_UNGRADED,
                        ReconciliationDiff.DETAIL_SUBJECTIVE_NOT_FULLY_GRADED,
                        ReconciliationDiff.STATUS_OPEN, null, null, null);
            }

            // 确定性缺失（判分完成但总分未回填）：无条件走同源条件更新，由 SQL 的
            // WHERE total_score IS NULL 自行判定「是否真的缺」。不在内存里先判空——
            // 内存判空会让「重复对账不重复补齐」的真实护栏在变异断言里不可见（第二次对账直接被跳过）。
            boolean written = scoreService.backfillTotalScoreIfMissing(submission.getId(), summary);
            if (written) {
                totals.autoFixed++;
                insertTicket(runId, submission, ReconciliationDiff.TYPE_SUBMISSION_UNGRADED,
                        ReconciliationDiff.DETAIL_TOTAL_SCORE_NOT_FILLED,
                        ReconciliationDiff.STATUS_AUTO_FIXED, null, null, null);
                auditLogService.logScoreReconcileAutoFix(examId, submission.getId(),
                        summary.totalScore().stripTrailingZeros().toPlainString());
                continue;
            }
            if (submission.getTotalScore() == null) {
                // 条件更新 0 行但库里仍为空：并发窗口/答卷状态不再满足，留日志不给错票
                log.warn("总分补齐未写入（并发已补齐或状态不满足）: exam={} submission={}",
                        examId, submission.getId());
                continue;
            }

            // 维度二：判分明细汇总 vs 最终总分，差异只出票，绝不自动改分
            if (summary.totalScore().compareTo(submission.getTotalScore()) != 0) {
                totals.mismatch++;
                insertTicket(runId, submission, ReconciliationDiff.TYPE_DETAIL_TOTAL_MISMATCH,
                        ReconciliationDiff.DETAIL_TOTAL_MISMATCH, ReconciliationDiff.STATUS_OPEN,
                        summary.totalScore(), submission.getTotalScore(),
                        summary.totalScore().subtract(submission.getTotalScore()));
            }
        }
    }

    /**
     * 出一张差异票。唯一指纹 (diff_type, submission_id, question_id, status) 让同一条未处置
     * 差异在重复对账时不重复出票——重复键即视为「已存在同状态票」，静默跳过。
     */
    private void insertTicket(Long runId, GradingSubmission submission, String diffType,
                              String detail, String status,
                              BigDecimal expected, BigDecimal actual, BigDecimal deviation) {
        ReconciliationDiff diff = new ReconciliationDiff();
        diff.setRunId(runId);
        diff.setExamId(submission.getExamId());
        diff.setSubmissionId(submission.getId());
        diff.setQuestionId(0L);
        diff.setDiffType(diffType);
        diff.setDetail(detail);
        diff.setExpectedValue(expected);
        diff.setActualValue(actual);
        diff.setDeviation(deviation);
        diff.setStatus(status);
        if (ReconciliationDiff.STATUS_MANUAL_CLOSED.equals(status)) {
            diff.setClosedBy(SecurityUtil.getUserId());
            diff.setClosedTime(LocalDateTime.now());
        }
        try {
            diffMapper.insert(diff);
        } catch (DuplicateKeyException e) {
            log.debug("差异票已存在，跳过: type={} submission={} status={}",
                    diffType, submission.getId(), status);
        }
    }

    // ==================== 私有工具 ====================

    private List<Long> candidateExamIds() {
        int limit = Math.max(maxExamsPerRun, 1);
        List<Long> examIds = examMapper.selectReconciliationCandidateExamIds(limit);
        return examIds == null ? List.of() : examIds;
    }

    private ReconciliationRun newRun(String triggerSource, Long examId, String batchKey, Long operatorId) {
        ReconciliationRun run = new ReconciliationRun();
        run.setExamId(examId);
        run.setTriggerSource(triggerSource);
        run.setBatchKey(batchKey);
        run.setStatus(ReconciliationRun.STATUS_RUNNING);
        run.setScannedCount(0);
        run.setUngradedCount(0);
        run.setMismatchCount(0);
        run.setAutoFixedCount(0);
        run.setOperatorId(operatorId);
        run.setStartedTime(LocalDateTime.now());
        return run;
    }

    private void finish(ReconciliationRun run, String status, Totals totals, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        run.setStatus(status);
        run.setScannedCount(totals.scanned);
        run.setUngradedCount(totals.ungraded);
        run.setMismatchCount(totals.mismatch);
        run.setAutoFixedCount(totals.autoFixed);
        run.setErrorMessage(truncate(errorMessage));
        run.setFinishedTime(now);
        runMapper.update(null, Wrappers.<ReconciliationRun>lambdaUpdate()
                .eq(ReconciliationRun::getId, run.getId())
                .set(ReconciliationRun::getStatus, status)
                .set(ReconciliationRun::getScannedCount, totals.scanned)
                .set(ReconciliationRun::getUngradedCount, totals.ungraded)
                .set(ReconciliationRun::getMismatchCount, totals.mismatch)
                .set(ReconciliationRun::getAutoFixedCount, totals.autoFixed)
                .set(ReconciliationRun::getErrorMessage, truncate(errorMessage))
                .set(ReconciliationRun::getFinishedTime, now)
                .set(ReconciliationRun::getUpdatedTime, now));
    }

    /** 定时批次键：按固定时间窗取整，多实例同一窗口得到同一键。 */
    private String batchKeyOf(LocalDateTime now) {
        long interval = Math.max(scheduledIntervalSeconds, 1);
        long window = now.toEpochSecond(ZoneOffset.UTC) / interval;
        return "SCHEDULED:" + window;
    }

    private long clampSize(long size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    /** 一次运行的累计差异数（跨考试聚合）。 */
    private static final class Totals {
        private int scanned;
        private int ungraded;
        private int mismatch;
        private int autoFixed;
    }
}
