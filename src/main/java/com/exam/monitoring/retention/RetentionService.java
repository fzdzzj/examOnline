package com.exam.monitoring.retention;

import com.exam.audit.mapper.AuditLogMapper;
import com.exam.exam.mapper.ExamMapper;
import com.exam.monitoring.metrics.BusinessMetrics;
import com.exam.score.mapper.ScoreAuditLogMapper;
import com.exam.submission.mapper.ExamBehaviorLogMapper;
import com.exam.submission.mapper.ExamSubmitDedupMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.ToIntBiFunction;
import java.util.function.ToLongFunction;

/**
 * 数据保留策略：按<strong>考试生命周期</strong>清理三张只增不减的诊断/幂等辅助表，
 * 外加按<strong>自身年龄</strong>清理的第四张 {@code audit_log}。
 *
 * <p><b>硬口径：宁可不删，不可错删。</b>
 * <ul>
 *   <li>{@code enabled} 默认 false：定时任务默认不跑；</li>
 *   <li>{@code dry-run} 默认 true：即使启用也只统计候选量、不执行 DELETE；</li>
 *   <li>启动时若 enabled=true 且 dry-run=false，打 WARN——真删不可能被静默开启。</li>
 * </ul>
 *
 * <p><b>按考试生命周期清理（仅此三张）</b>：
 * {@code exam_behavior_logs} / {@code exam_submit_dedups} / {@code score_audit_logs}。
 * 删除条件只用 {@code exam_id}，命中既有索引最左前缀。
 *
 * <p><b>{@code audit_log} 走另一条路</b>：它是安全审计，没有 exam_id，不参与考试生命周期，
 * 只按 {@code created_time} 超窗清理，且保留窗口独立配置（{@code audit-log.retention-days}）。
 * 原先"禁止 {@code WHERE created_time < ?}"的规矩针对的是<strong>无索引</strong>的全表扫描；
 * 这张表已补 {@code idx_audit_time}，故本条是"先加索引才可按时删"，不是破例。
 * 缺索引时连"没有东西可删"的稳态都要读穿全表（10 万行实测 34ms，随行数线性增长）。
 *
 * <p><b>绝对不清理的业务事实</b>（写进注释作为边界护栏）：
 * users / roles / permissions / questions / papers / paper_snapshots / exams / exam_snapshots /
 * exam_submissions / subjective_grades / exam_absence / exam_candidates / score_review /
 * classes / user_class。
 * 理由：答卷与成绩有可追溯义务（复核窗口、补考取分规则要回看）；快照是只读历史；
 * 关系表是当前归属（清掉会改变应考名单口径，属业务破坏）。
 *
 * <p><b>不纳入 {@code exam_dlq_messages}</b>：该表已存在，但没有 exam_id 列，索引是
 * {@code idx_dlq_status_time (status, created_time)}。按 exam_id 删会全表扫描或要加列——
 * 都违反「零 DDL + 按考试生命周期」。本轮明确不写对该表的 DELETE。
 *
 * <p><b>候选口径</b>：{@code status >= 2 AND end_time < cutoff}。
 * 用 end_time 不用 updated_time；force-end 会晚删，误差方向安全。进行中（status=1）一律不碰。
 *
 * <p><b>有界删除</b>：每批短事务（不把整次 purgeOnce 包成大事务），每批 batch-size 行、
 * 每张表每次运行最多 max-batches-per-run 批。多实例不加分布式锁：重复跑第二次 0 行，幂等。
 *
 * <p><b>不声称磁盘释放</b>：InnoDB DELETE 只标记页可复用，文件不会变小；真正回收需离线 OPTIMIZE。
 */
@Slf4j
@Service
public class RetentionService {

    static final String TABLE_BEHAVIOR = "exam_behavior_logs";
    static final String TABLE_DEDUP = "exam_submit_dedups";
    static final String TABLE_AUDIT = "score_audit_logs";
    static final String TABLE_SECURITY_AUDIT = "audit_log";

    private final ExamMapper examMapper;
    private final ExamBehaviorLogMapper behaviorLogMapper;
    private final ExamSubmitDedupMapper submitDedupMapper;
    private final ScoreAuditLogMapper scoreAuditLogMapper;
    private final AuditLogMapper auditLogMapper;
    private final BusinessMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    @Value("${exam.retention.enabled:false}")
    private boolean enabled;

    @Value("${exam.retention.dry-run:true}")
    private boolean dryRun;

    @Value("${exam.retention.retention-days:180}")
    private int retentionDays;

    /**
     * 安全审计（{@code audit_log}）保留窗口，与上面三张辅助表<strong>各自独立</strong>：
     * 登录事件的取证价值窗口和考试生命周期不是一回事。
     * 默认 365 天只是"有个上界"的占位口径，<b>真实数值属合规决策，须由业务/安全口径确认后才能改</b>；
     * 在该口径确认前，{@code enabled=false} + {@code dry-run=true} 的默认闸门保证不会静默删掉审计。
     */
    @Value("${exam.retention.audit-log.retention-days:365}")
    private int auditLogRetentionDays;

    @Value("${exam.retention.batch-size:1000}")
    private int batchSize;

    @Value("${exam.retention.max-batches-per-run:20}")
    private int maxBatchesPerRun;

    /** 单次运行最多处理的候选考试数（有界，避免一次扫过大）。 */
    @Value("${exam.retention.max-exams-per-run:100}")
    private int maxExamsPerRun;

    public RetentionService(ExamMapper examMapper,
                            ExamBehaviorLogMapper behaviorLogMapper,
                            ExamSubmitDedupMapper submitDedupMapper,
                            ScoreAuditLogMapper scoreAuditLogMapper,
                            AuditLogMapper auditLogMapper,
                            BusinessMetrics metrics,
                            PlatformTransactionManager transactionManager) {
        this.examMapper = examMapper;
        this.behaviorLogMapper = behaviorLogMapper;
        this.submitDedupMapper = submitDedupMapper;
        this.scoreAuditLogMapper = scoreAuditLogMapper;
        this.auditLogMapper = auditLogMapper;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 启动时若本实例会真删数据，必须打 WARN——运维决策不可静默。
     */
    @PostConstruct
    void warnIfLiveDeleteEnabled() {
        if (enabled && !dryRun) {
            log.warn("数据保留清理已启用真删除：enabled=true dry-run=false retentionDays={} "
                            + "batchSize={} maxBatchesPerRun={} maxExamsPerRun={} "
                            + "——将按考试生命周期物理删除三张辅助表行，不可逆",
                    retentionDays, batchSize, maxBatchesPerRun, maxExamsPerRun);
        }
    }

    /**
     * 定时入口：仅在 enabled=true 时调用 {@link #purgeOnce()}。
     * 业务逻辑不写在定时方法里，便于测试直接调 purgeOnce。
     */
    @Scheduled(cron = "${exam.retention.cron:0 30 3 * * *}")
    public void scheduledPurge() {
        if (!enabled) {
            return;
        }
        purgeOnce();
    }

    /**
     * 执行一轮保留清理（或 dry-run 试算）。
     * 逐考试、分批短事务；单表失败记 ERROR 后继续下一考试/下一表。
     */
    public RetentionReport purgeOnce() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(retentionDays, 0));
        int examLimit = Math.max(maxExamsPerRun, 1);
        int batch = Math.max(batchSize, 1);
        int maxBatches = Math.max(maxBatchesPerRun, 1);

        List<Long> examIds = examMapper.selectRetentionCandidateExamIds(cutoff, examLimit);
        long behaviorCandidates = 0;
        long behaviorDeleted = 0;
        long dedupCandidates = 0;
        long dedupDeleted = 0;
        long auditCandidates = 0;
        long auditDeleted = 0;

        for (Long examId : examIds) {
            if (examId == null) {
                continue;
            }
            try {
                TablePurgeResult behavior = purgeTable(
                        TABLE_BEHAVIOR, examId, batch, maxBatches,
                        behaviorLogMapper::countByExamId, behaviorLogMapper::deleteByExamIdBatch);
                behaviorCandidates += behavior.candidates();
                behaviorDeleted += behavior.deleted();
            } catch (Exception e) {
                log.error("保留清理失败 table={} examId={}", TABLE_BEHAVIOR, examId, e);
            }
            try {
                TablePurgeResult dedup = purgeTable(
                        TABLE_DEDUP, examId, batch, maxBatches,
                        submitDedupMapper::countByExamId, submitDedupMapper::deleteByExamIdBatch);
                dedupCandidates += dedup.candidates();
                dedupDeleted += dedup.deleted();
            } catch (Exception e) {
                log.error("保留清理失败 table={} examId={}", TABLE_DEDUP, examId, e);
            }
            try {
                TablePurgeResult audit = purgeTable(
                        TABLE_AUDIT, examId, batch, maxBatches,
                        scoreAuditLogMapper::countByExamId, scoreAuditLogMapper::deleteByExamIdBatch);
                auditCandidates += audit.candidates();
                auditDeleted += audit.deleted();
            } catch (Exception e) {
                log.error("保留清理失败 table={} examId={}", TABLE_AUDIT, examId, e);
            }
        }

        long securityAuditCandidates = 0;
        long securityAuditDeleted = 0;
        try {
            LocalDateTime auditCutoff =
                    LocalDateTime.now().minusDays(Math.max(auditLogRetentionDays, 0));
            securityAuditCandidates = auditLogMapper.countOlderThan(auditCutoff);
            metrics.countRetentionRows(TABLE_SECURITY_AUDIT, "candidate", securityAuditCandidates);
            if (!dryRun && securityAuditCandidates > 0) {
                for (int i = 0; i < maxBatches; i++) {
                    Integer n = transactionTemplate.execute(
                            status -> auditLogMapper.deleteOlderThanBatch(auditCutoff, batch));
                    int rows = n == null ? 0 : n;
                    if (rows <= 0) {
                        break;
                    }
                    securityAuditDeleted += rows;
                    if (rows < batch) {
                        break;
                    }
                }
                if (securityAuditDeleted > 0) {
                    metrics.countRetentionRows(TABLE_SECURITY_AUDIT, "deleted", securityAuditDeleted);
                }
            }
        } catch (Exception e) {
            log.error("保留清理失败 table={}", TABLE_SECURITY_AUDIT, e);
        }

        RetentionReport report = new RetentionReport(
                dryRun,
                examIds.size(),
                behaviorCandidates,
                behaviorDeleted,
                dedupCandidates,
                dedupDeleted,
                auditCandidates,
                auditDeleted,
                securityAuditCandidates,
                securityAuditDeleted);

        log.info("保留清理完成 dryRun={} examsProcessed={} "
                        + "behavior(c/d)={}/{} dedup(c/d)={}/{} audit(c/d)={}/{} "
                        + "securityAudit(c/d)={}/{} total(c/d)={}/{}",
                report.dryRun(),
                report.examsProcessed(),
                report.behaviorCandidates(), report.behaviorDeleted(),
                report.dedupCandidates(), report.dedupDeleted(),
                report.auditCandidates(), report.auditDeleted(),
                report.securityAuditCandidates(), report.securityAuditDeleted(),
                report.totalCandidates(), report.totalDeleted());
        return report;
    }

    private TablePurgeResult purgeTable(String table,
                                        Long examId,
                                        int batch,
                                        int maxBatches,
                                        ToLongFunction<Long> counter,
                                        ToIntBiFunction<Long, Integer> deleter) {
        long candidates = counter.applyAsLong(examId);
        if (candidates > 0) {
            metrics.countRetentionRows(table, "candidate", candidates);
        }
        if (dryRun || candidates == 0) {
            return new TablePurgeResult(candidates, 0);
        }

        long deleted = 0;
        for (int i = 0; i < maxBatches; i++) {
            Integer n = transactionTemplate.execute(status -> deleter.applyAsInt(examId, batch));
            int rows = n == null ? 0 : n;
            if (rows <= 0) {
                break;
            }
            deleted += rows;
            if (rows < batch) {
                break;
            }
        }
        if (deleted > 0) {
            metrics.countRetentionRows(table, "deleted", deleted);
        }
        return new TablePurgeResult(candidates, deleted);
    }

    private record TablePurgeResult(long candidates, long deleted) {
    }
}
