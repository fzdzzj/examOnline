package com.exam.submission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.submission.entity.ExamDlqMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 死信消息留档 Mapper：留档写入之外的读取与按状态+年龄有界清理（harden-data-retention-scope）。
 *
 * <p>清理面只允许 {@code (status, created_time)} 双条件：完全命中既有复合索引
 * {@code idx_dlq_status_time (status, created_time)} 最左前缀，零 DDL、不发生全表扫描。
 */
@Mapper
public interface ExamDlqMessageMapper extends BaseMapper<ExamDlqMessage> {

    /** 指定状态、超过保留窗口的死信留档行数（dry-run 也统计）。 */
    @Select("SELECT COUNT(*) FROM exam_dlq_messages WHERE status = #{status} AND created_time < #{cutoff}")
    long countByStatusOlderThan(@Param("status") String status, @Param("cutoff") LocalDateTime cutoff);

    /**
     * 按状态与年龄分批删除（保留策略）。
     *
     * <p>status 由调用方固定传 {@link ExamDlqMessage#STATUS_REPLAYED}：已重放成功的留档才有
     * 清理资格，{@link ExamDlqMessage#STATUS_PARKED}（超重投上限待人工排查）绝不在删除面内——
     * 该状态是唯一的事故现场证据，删掉等于销毁排查线索。
     *
     * <p>不写 {@code ORDER BY created_time}：H2 的 DELETE 不支持该子句，且此处所有匹配行
     * 都已超窗，先删哪批不影响结果。
     */
    @Delete("DELETE FROM exam_dlq_messages WHERE status = #{status} AND created_time < #{cutoff} LIMIT #{limit}")
    int deleteByStatusOlderThanBatch(@Param("status") String status,
                                     @Param("cutoff") LocalDateTime cutoff,
                                     @Param("limit") int limit);
}
