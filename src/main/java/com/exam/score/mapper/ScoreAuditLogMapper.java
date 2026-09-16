package com.exam.score.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.score.entity.ScoreAuditLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 成绩审计日志 Mapper：只写不改（append-only），无业务更新 SQL；
 * 保留策略提供按 exam_id 的 count/分批删除（命中既有 idx_score_audit_exam）。
 */
@Mapper
public interface ScoreAuditLogMapper extends BaseMapper<ScoreAuditLog> {

    /**
     * 按考试统计审计日志行数（保留策略试算）。
     * WHERE 只带 exam_id，命中 idx_score_audit_exam (exam_id)。
     */
    @Select("SELECT COUNT(*) FROM score_audit_logs WHERE exam_id = #{examId}")
    long countByExamId(@Param("examId") Long examId);

    /**
     * 按考试分批删除审计日志（保留策略）。
     * <p>WHERE 只带 exam_id（命中 idx_score_audit_exam (exam_id)），
     * 故意不用 created_time：该列无索引，WHERE created_time &lt; ? 会全表扫描。
     */
    @Delete("DELETE FROM score_audit_logs WHERE exam_id = #{examId} LIMIT #{limit}")
    int deleteByExamIdBatch(@Param("examId") Long examId, @Param("limit") int limit);
}
