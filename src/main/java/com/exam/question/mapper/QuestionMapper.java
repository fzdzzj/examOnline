package com.exam.question.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.question.entity.Question;
import org.apache.ibatis.annotations.Mapper;

/** 题目 Mapper：CRUD 由 BaseMapper 提供（逻辑删除自动生效）。 */
@Mapper
public interface QuestionMapper extends BaseMapper<Question> {
}
