package com.exam.reliability.reconciliation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.reliability.reconciliation.entity.ReconciliationDiff;
import org.apache.ibatis.annotations.Mapper;

/**
 * 差异票 Mapper（reconciliation_diffs）：
 * 常规读写走 MyBatis-Plus Wrapper；重复出票由
 * {@code uk_recon_diff_fingerprint(diff_type, submission_id, question_id, status)} 唯一索引兜底，
 * 调用方捕获重复键异常后按「同一差异已存在未处置票」跳过。
 */
@Mapper
public interface ReconciliationDiffMapper extends BaseMapper<ReconciliationDiff> {
}
