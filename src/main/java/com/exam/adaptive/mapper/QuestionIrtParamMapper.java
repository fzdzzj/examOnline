package com.exam.adaptive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.adaptive.entity.QuestionIrtParam;
import org.apache.ibatis.annotations.Mapper;

/** 题目 IRT 参数 Mapper（表 question_irt_params，见 schema.sql）。 */
@Mapper
public interface QuestionIrtParamMapper extends BaseMapper<QuestionIrtParam> {
}
