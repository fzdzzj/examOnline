package com.exam.paper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.paper.entity.PaperQuestion;
import org.apache.ibatis.annotations.Mapper;

/** 试卷-题目关联 Mapper。 */
@Mapper
public interface PaperQuestionMapper extends BaseMapper<PaperQuestion> {
}
