package com.exam.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.message.entity.UserMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 站内消息 Mapper：常规读写走 MyBatis-Plus；标记已读走显式 UPDATE（仅本人行，user_id 隔离）。
 */
@Mapper
public interface UserMessageMapper extends BaseMapper<UserMessage> {

    /**
     * 单条标记已读（仅本人且未读才更新）：WHERE user_id=#{userId} AND read_flag=0。
     * 返回影响行数——0 表示该消息不存在/不属于本人/已读（幂等）。
     */
    @Update("UPDATE user_messages SET read_flag = 1 "
            + "WHERE id = #{id} AND user_id = #{userId} AND read_flag = 0")
    int markReadByOwner(@Param("id") Long id, @Param("userId") Long userId);
}