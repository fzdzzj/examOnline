package com.exam.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
