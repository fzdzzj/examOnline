package com.exam.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.audit.entity.AuditLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 安全审计日志 Mapper：登录/锁定等安全事件的写入与按年龄有界清理。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /** 超过保留窗口的审计行数（dry-run 也统计）。 */
    @Select("SELECT COUNT(*) FROM audit_log WHERE created_time < #{cutoff}")
    long countOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 按年龄分批删除（保留策略）。
     *
     * <p>{@code created_time} 上必须有 {@code idx_audit_time}：没有它，"没有东西可删"这一
     * 稳态也要把整张表读穿才敢返回 0（10 万行实测 34ms，且随行数线性增长）；
     * 有索引则 0.16–0.7ms 且与表大小无关。见 schema.sql 与
     * {@code docker/mysql/migrations/2026-W38-add-audit-log-time-index.sql}。
     *
     * <p>不写 {@code ORDER BY created_time}：H2 的 DELETE 不支持该子句，且此处所有匹配行
     * 都已超窗，先删哪批不影响结果。
     */
    @Delete("DELETE FROM audit_log WHERE created_time < #{cutoff} LIMIT #{limit}")
    int deleteOlderThanBatch(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
