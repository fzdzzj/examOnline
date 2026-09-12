package com.exam.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.user.entity.Permission;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {
}
