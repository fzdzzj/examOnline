package com.exam.clazz.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.clazz.entity.UserClass;
import org.apache.ibatis.annotations.Mapper;

/** 学生-班级关联 Mapper。 */
@Mapper
public interface UserClassMapper extends BaseMapper<UserClass> {
}
