package com.exam.clazz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.clazz.entity.ClassEntity;
import org.apache.ibatis.annotations.Mapper;

/** 班级 Mapper。 */
@Mapper
public interface ClassMapper extends BaseMapper<ClassEntity> {
}
