package com.exam.monitoring.retention;

/**
 * 一次保留清理运行的汇总报告（每表 candidates/deleted、处理考试数、是否 dry-run）。
 *
 * <p>candidates 在 dry-run 与真实删除模式下都会统计；deleted 仅在 dry-run=false 时可能非零。
 *
 * <p>{@code securityAudit*} 是第四张表 {@code audit_log}：它没有 exam_id，不参与考试生命周期，
 * 只按自身保留窗口按年龄清理，口径独立于前三张。
 *
 * <p>{@code dlq*} 是第五张表 {@code exam_dlq_messages}：只清 {@code status = 'REPLAYED'}
 * （已重放成功）的死信留档，按自身窗口（{@code dlq.retention-days}）清理；
 * 待人工排查的 {@code PARKED} 绝不进入候选。与 audit_log 同属"无 exam_id、独立窗口"家族，
 * 不计入下面的 lifecycle 合计。
 */
public record RetentionReport(
        boolean dryRun,
        int examsProcessed,
        long behaviorCandidates,
        long behaviorDeleted,
        long dedupCandidates,
        long dedupDeleted,
        long auditCandidates,
        long auditDeleted,
        long securityAuditCandidates,
        long securityAuditDeleted,
        long dlqCandidates,
        long dlqDeleted
) {
    /** 三张按考试生命周期清理的辅助表候选行数合计（不含 audit_log 与 exam_dlq_messages）。 */
    public long totalCandidates() {
        return behaviorCandidates + dedupCandidates + auditCandidates;
    }

    /** 三张按考试生命周期清理的辅助表实际删除行数合计（dry-run 下恒为 0，不含 audit_log 与 exam_dlq_messages）。 */
    public long totalDeleted() {
        return behaviorDeleted + dedupDeleted + auditDeleted;
    }
}
