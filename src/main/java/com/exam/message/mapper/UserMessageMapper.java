package com.exam.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.message.entity.UserMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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

    /**
     * 全部标记已读（add-exam-lifecycle-notifications）：仅本人（user_id 隔离），
     * 未读行（read_flag=0）批量置 1；已读/不存在不受影响。返回受影响行数（可为 0）。
     */
    @Update("UPDATE user_messages SET read_flag = 1 "
            + "WHERE user_id = #{userId} AND read_flag = 0")
    int markAllByOwner(@Param("userId") Long userId);

    /**
     * 批量写入站内消息（optimize-backend-batch-write-paths）：显式全列 INSERT，
     * 列清单与 {@code schema.sql} 的 {@code user_messages} 建表定义逐列一致
     * （user_id/exam_id/title/content/read_flag/created_time），不依赖 MyBatis-Plus 字段推导，
     * 规避实体-表漂移面。沿用本 Mapper 既有显式注解 SQL 惯例。
     *
     * <p>自定义 {@code @Insert} 不经过 MyBatis-Plus 字段填充，created_time 须由调用方显式赋值
     * （对齐 {@code ExamCandidateMapper.insertIgnoreBatch} 惯例）。批次大小由调用方控制
     * （每批 ≤500 行），防单语句超 {@code max_allowed_packet}。
     *
     * @return 实际写入行数
     */
    @Insert("<script>INSERT INTO user_messages "
            + "(user_id, exam_id, title, content, read_flag, created_time) VALUES "
            + "<foreach collection='messages' item='m' separator=','>"
            + "(#{m.userId}, #{m.examId}, #{m.title}, #{m.content}, #{m.readFlag}, #{m.createdTime})"
            + "</foreach></script>")
    int insertBatch(@Param("messages") List<UserMessage> messages);
}