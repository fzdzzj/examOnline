package com.exam.grading.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.grading.dto.SubjectiveGradeRow;
import com.exam.grading.entity.SubjectiveGrade;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * 主观题批改 Mapper：常规 CRUD 走 MyBatis-Plus，工作台联查与终分 CAS 走自定义 SQL。
 */
@Mapper
public interface SubjectiveGradeMapper extends BaseMapper<SubjectiveGrade> {

    /**
     * 教师批改终分 CAS（spec「并发批改防覆盖」场景）：
     * 仅当 version 与读取时一致才写入终分/评语/批改人/时间，返回影响行数——
     * 两教师并发批改同一答卷同题，仅一个影响行数为 1（成功），另一个影响 0 行（报冲突重载）。
     * version 自增放在 SET 中由数据库原子完成，杜绝读改写竞态。
     */
    @Update("UPDATE subjective_grades SET score = #{score}, comment = #{comment}, "
            + "grader_id = #{graderId}, graded_time = CURRENT_TIMESTAMP, "
            + "version = version + 1, updated_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND version = #{expectedVersion}")
    int casSaveScore(@Param("id") Long id,
                     @Param("score") BigDecimal score,
                     @Param("comment") String comment,
                     @Param("graderId") Long graderId,
                     @Param("expectedVersion") Integer expectedVersion);

    /**
     * 批改工作台联查：同题列出全部学生（含学生姓名），按学生 ID 排序保证列表稳定。
     * 学生姓名来自 users 表（批改展示需要，批改表不冗余姓名）；列名驼峰映射到 DTO。
     */
    @Select("SELECT g.id, g.submission_id, g.exam_id, g.student_id, g.question_id, "
            + "g.question_number, g.student_answer, g.suggested_score, g.suggested_detail, "
            + "g.score, g.comment, g.grader_id, g.graded_time, g.version, u.name AS student_name "
            + "FROM subjective_grades g "
            + "JOIN users u ON u.id = g.student_id "
            + "WHERE g.exam_id = #{examId} AND g.question_id = #{questionId} "
            + "ORDER BY g.student_id")
    List<SubjectiveGradeRow> selectWorkbenchRows(@Param("examId") Long examId,
                                                 @Param("questionId") Long questionId);

    /**
     * 批改工作台分页联查（add-subjective-grading-pagination）：
     * 列与 {@link #selectWorkbenchRows} 完全一致（保存后回读仍走原查询，主库回读口径零改动）；
     * 新增筛选下沉（onlyUngraded/name/submissionId，均可空）与服务端分页
     * （offset/limit 均空 = 缺省全量）。排序保持 ORDER BY g.student_id——翻页不丢行不错行的前提。
     */
    @Select("<script>"
            + "SELECT g.id, g.submission_id, g.exam_id, g.student_id, g.question_id, "
            + "g.question_number, g.student_answer, g.suggested_score, g.suggested_detail, "
            + "g.score, g.comment, g.grader_id, g.graded_time, g.version, u.name AS student_name "
            + "FROM subjective_grades g "
            + "JOIN users u ON u.id = g.student_id "
            + "WHERE g.exam_id = #{examId} AND g.question_id = #{questionId} "
            + "<if test='onlyUngraded != null and onlyUngraded'> AND g.score IS NULL </if>"
            + "<if test='name != null and name != \"\"'> AND u.name LIKE CONCAT('%', #{name}, '%') </if>"
            + "<if test='submissionId != null'> AND g.submission_id = #{submissionId} </if>"
            + "ORDER BY g.student_id "
            + "<if test='offset != null and limit != null'> LIMIT #{limit} OFFSET #{offset} </if>"
            + "</script>")
    List<SubjectiveGradeRow> selectWorkbenchRowsPaged(@Param("examId") Long examId,
                                                      @Param("questionId") Long questionId,
                                                      @Param("offset") Integer offset,
                                                      @Param("limit") Integer limit,
                                                      @Param("onlyUngraded") Boolean onlyUngraded,
                                                      @Param("name") String name,
                                                      @Param("submissionId") Long submissionId);

    /**
     * 行总数计数：WHERE 与 {@link #selectWorkbenchRowsPaged} 完全一致（筛选后、分页前），
     * 前端据此计算服务端分页页数。
     */
    @Select("<script>"
            + "SELECT COUNT(*) FROM subjective_grades g "
            + "JOIN users u ON u.id = g.student_id "
            + "WHERE g.exam_id = #{examId} AND g.question_id = #{questionId} "
            + "<if test='onlyUngraded != null and onlyUngraded'> AND g.score IS NULL </if>"
            + "<if test='name != null and name != \"\"'> AND u.name LIKE CONCAT('%', #{name}, '%') </if>"
            + "<if test='submissionId != null'> AND g.submission_id = #{submissionId} </if>"
            + "</script>")
    long countWorkbenchRows(@Param("examId") Long examId,
                            @Param("questionId") Long questionId,
                            @Param("onlyUngraded") Boolean onlyUngraded,
                            @Param("name") String name,
                            @Param("submissionId") Long submissionId);

    /**
     * 已批行数计数：口径 = {@code score IS NOT NULL} 的行数（该题全量，不受行筛选影响），
     * 与题级进度 SubjectiveQuestionItem.gradedStudents 同口径。
     */
    @Select("SELECT COUNT(*) FROM subjective_grades "
            + "WHERE exam_id = #{examId} AND question_id = #{questionId} AND score IS NOT NULL")
    long countWorkbenchGraded(@Param("examId") Long examId, @Param("questionId") Long questionId);
}
