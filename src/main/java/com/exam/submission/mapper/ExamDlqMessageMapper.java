package com.exam.submission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.submission.entity.ExamDlqMessage;
import org.apache.ibatis.annotations.Mapper;

/** 死信消息留档 Mapper。 */
@Mapper
public interface ExamDlqMessageMapper extends BaseMapper<ExamDlqMessage> {
}
