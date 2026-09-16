package com.exam.monitoring.retention;

/**
 * 一次保留清理运行的汇总报告（每表 candidates/deleted、处理考试数、是否 dry-run）。
 *
 * <p>candidates 在 dry-run 与真实删除模式下都会统计；deleted 仅在 dry-run=false 时可能非零。
 */
public record RetentionReport(
        boolean dryRun,
        int examsProcessed,
        long behaviorCandidates,
        long behaviorDeleted,
        long dedupCandidates,
        long dedupDeleted,
        long auditCandidates,
        long auditDeleted
) {
    /** 三张辅助表候选行数合计。 */
    public long totalCandidates() {
        return behaviorCandidates + dedupCandidates + auditCandidates;
    }

    /** 三张辅助表实际删除行数合计（dry-run 下恒为 0）。 */
    public long totalDeleted() {
        return behaviorDeleted + dedupDeleted + auditDeleted;
    }
}
