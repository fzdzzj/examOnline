package com.exam.reliability.reconciliation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.reliability.reconciliation.entity.ReconciliationRun;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对账运行记录 Mapper（reconciliation_runs）：
 * 常规读写走 MyBatis-Plus Wrapper；定时批次的「单飞」护栏依赖
 * {@code uk_recon_run_batch(batch_key)} 唯一索引，重复插入由调用方捕获重复键异常。
 */
@Mapper
public interface ReconciliationRunMapper extends BaseMapper<ReconciliationRun> {
}
