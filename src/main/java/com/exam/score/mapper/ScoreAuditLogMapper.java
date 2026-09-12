package com.exam.score.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.score.entity.ScoreAuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 成绩审计日志 Mapper：只写不改（append-only），无自定义 SQL。
 */
@Mapper
public interface ScoreAuditLogMapper extends BaseMapper<ScoreAuditLog> {
}
