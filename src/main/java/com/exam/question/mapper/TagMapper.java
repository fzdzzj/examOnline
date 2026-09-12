package com.exam.question.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.question.entity.Tag;
import org.apache.ibatis.annotations.Mapper;

/** 标签 Mapper。 */
@Mapper
public interface TagMapper extends BaseMapper<Tag> {
}
