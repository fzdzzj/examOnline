package com.exam.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.exam.entity.ExamReservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 考试预约 Mapper：常规读写走 MyBatis-Plus；开考提醒投递走自定义 UPDATE 影响行数判等幂等。
 */
@Mapper
public interface ExamReservationMapper extends BaseMapper<ExamReservation> {

    /**
     * 开考提醒投递的行级幂等标记：仅当该预约行 reminded_time 仍为 NULL（尚未投递）时回填。
     * 返回影响行数——多个实例/多次扫描并发竞争同一行时，仅一个影响行数为 1，其余影响 0 行，
     * 投递方才写入消息。这是行级幂等的持久化闸（仿 exam_submissions 的 casUpdateStatus 语义）。
     *
     * <p>自定义 SQL 不经过 MyBatis-Plus 拦截器，无需 @TableLogic 处理（本表无软删）。
     *
     * @return 影响行数：1=本次赢得投递权（应写消息）；0=已被并发投递或非候选，跳过
     */
    @Update("UPDATE exam_reservations SET reminded_time = #{now} "
            + "WHERE id = #{id} AND reminded_time IS NULL")
    int markRemindedIfNull(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 按考试取有效预约（examId 存在性/开考锁定后的名单来源）。
     * 走 idx_reservations_exam 索引；Used 由 service 层显式投列。
     */
    default List<ExamReservation> selectByExam(Long examId) {
        return selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExamReservation>()
                .eq(ExamReservation::getExamId, examId));
    }
}