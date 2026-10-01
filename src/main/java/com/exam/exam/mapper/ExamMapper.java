package com.exam.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.exam.entity.Exam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 考试 Mapper：常规 CRUD 走 MyBatis-Plus，状态流转走自定义 CAS SQL。
 */
@Mapper
public interface ExamMapper extends BaseMapper<Exam> {

    /**
     * 状态机乐观锁 CAS（spec「并发流转仅一次」场景）：
     * 仅当"当前状态与版本号都与读取时一致"才更新，返回影响行数——
     * 两个并发请求同时迁移同一考试时，仅一个影响行数为 1，另一个影响 0 行（重试或报错）。
     *
     * <p>自定义 SQL 不经过 MyBatis-Plus 拦截器，需显式带上 is_deleted=0 与 updated_time；
     * version 自增放在 SET 中由数据库原子完成，无需读改写。
     */
    @Update("UPDATE exams SET status = #{newStatus}, version = version + 1, "
            + "updated_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = #{expectedStatus} AND version = #{version} AND is_deleted = 0")
    int casUpdateStatus(@Param("id") Long id,
                        @Param("expectedStatus") int expectedStatus,
                        @Param("newStatus") int newStatus,
                        @Param("version") int version);

    /**
     * 数据保留候选考试：status &gt;= 2（已结束/已批改/已发布）且 end_time 早于 cutoff。
     * 进行中（status=1）一律不返回，哪怕 end_time 已过——异常态交给人处理，清理任务不替它决定。
     * 用 end_time 不用 updated_time：updated_time 会被任意编辑刷新，不表达终结时刻。
     */
    @Select("SELECT id FROM exams WHERE status >= 2 AND end_time < #{cutoff} ORDER BY end_time ASC, id ASC LIMIT #{limit}")
    List<Long> selectRetentionCandidateExamIds(@Param("cutoff") LocalDateTime cutoff,
                                               @Param("limit") int limit);
}
