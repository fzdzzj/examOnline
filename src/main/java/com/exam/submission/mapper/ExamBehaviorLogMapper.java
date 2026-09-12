package com.exam.submission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.submission.entity.ExamBehaviorLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 考试行为日志 Mapper：切屏/失焦等事件的落库通道（只写不查，阶段 7 防作弊消费）。
 */
@Mapper
public interface ExamBehaviorLogMapper extends BaseMapper<ExamBehaviorLog> {
}
