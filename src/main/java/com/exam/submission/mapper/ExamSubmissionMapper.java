package com.exam.submission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.submission.entity.ExamSubmission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 答卷 Mapper：常规 CRUD 走 MyBatis-Plus，状态迁移/答案落库/兜底扫描走自定义 SQL。
 */
@Mapper
public interface ExamSubmissionMapper extends BaseMapper<ExamSubmission> {

    /**
     * 答卷状态机 CAS（spec「三路竞态仅一次」场景）：
     * 仅当仍处于进行中才迁移为已交卷，返回影响行数——手动/前端归零/后端兜底三路并发提交时，
     * 仅一路影响行数为 1，其余为 0（幂等返回首次结果），杜绝重复交卷与状态脏写。
     *
     * <p>自定义 SQL 不经过 MyBatis-Plus 拦截器，需显式带上 updated_time；version 自增由数据库原子完成。
     */
    @Update("UPDATE exam_submissions SET status = #{newStatus}, submit_time = #{submitTime}, "
            + "submit_type = #{submitType}, version = version + 1, updated_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = #{expectedStatus}")
    int casSubmit(@Param("id") Long id,
                  @Param("expectedStatus") int expectedStatus,
                  @Param("newStatus") int newStatus,
                  @Param("submitTime") LocalDateTime submitTime,
                  @Param("submitType") int submitType);

    /**
     * 交卷答案落库（消费端幂等）：仅当 answers 仍为 NULL 才写入——
     * 相同消息重复投递时影响 0 行，业务只执行一次（spec「重复投递幂等」场景）。
     */
    @Update("UPDATE exam_submissions SET answers = #{answers}, updated_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND answers IS NULL")
    int casFillAnswers(@Param("id") Long id, @Param("answers") String answers);

    /**
     * 兜底扫描（服务端时间为准）：进行中且"个人已超时"或"所属考试已结束/已批改"的答卷，
     * 由定时任务强制交卷（spec「后端兜底」场景；教师提前结束 force_end 后考试态为已结束，同样命中）。
     * 联表判断考试状态——答卷表不冗余考试状态，以 exams 为唯一事实源。
     */
    @Select("SELECT s.* FROM exam_submissions s "
            + "JOIN exams e ON e.id = s.exam_id AND e.is_deleted = 0 "
            + "WHERE s.status = 1 AND (s.deadline_time < #{now} OR e.status IN (2, 3)) "
            + "LIMIT #{limit}")
    List<ExamSubmission> selectForceSubmitCandidates(@Param("now") LocalDateTime now,
                                                     @Param("limit") int limit);

    /**
     * 对账补发扫描：已交卷但 answers 尚未落库（MQ 发送失败/消费重试耗尽进死信等极端场景），
     * 定时任务重新投递交卷消息，消费端 casFillAnswers 幂等，不会重复写。
     */
    @Select("SELECT * FROM exam_submissions WHERE status = 2 AND answers IS NULL LIMIT #{limit}")
    List<ExamSubmission> selectSubmittedWithoutAnswers(@Param("limit") int limit);

    /** 按 (考试, 学生) 定位答卷——幂等进入/交卷/自动保存的统一查询入口。 */
    default ExamSubmission selectByExamStudent(Long examId, Long studentId) {
        return selectOne(Wrappers.<ExamSubmission>lambdaQuery()
                .eq(ExamSubmission::getExamId, examId)
                .eq(ExamSubmission::getStudentId, studentId));
    }
}
