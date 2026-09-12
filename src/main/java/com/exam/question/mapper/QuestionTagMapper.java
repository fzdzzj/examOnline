package com.exam.question.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.question.entity.QuestionTag;
import org.apache.ibatis.annotations.Mapper;

/** 题目-标签关联 Mapper。 */
@Mapper
public interface QuestionTagMapper extends BaseMapper<QuestionTag> {
}
