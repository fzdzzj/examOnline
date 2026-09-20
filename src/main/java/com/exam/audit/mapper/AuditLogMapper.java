package com.exam.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.audit.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 安全审计日志 Mapper：只写不读，查询走后续管理端接口。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
