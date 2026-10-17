package com.exam.question.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exam.question.dto.StudentQuestionFavoriteItem;
import com.exam.question.entity.StudentQuestionFavorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface StudentQuestionFavoriteMapper extends BaseMapper<StudentQuestionFavorite> {

    @Select("<script>"
            + "SELECT f.id AS id, f.student_id AS studentId, f.question_id AS questionId, f.created_time AS createdTime, "
            + "q.type AS type, q.content AS content, q.image_url AS imageUrl, q.choices AS choices, "
            + "q.correct_answer AS correctAnswer, q.score AS score, q.difficulty AS difficulty, q.analysis AS analysis "
            + "FROM student_question_favorites f "
            + "JOIN questions q ON f.question_id = q.id AND q.is_deleted = 0 "
            + "WHERE f.student_id = #{studentId} "
            + "<if test='type != null'> AND q.type = #{type} </if>"
            + "<if test='keyword != null and keyword.trim() != \"\"'> AND q.content LIKE CONCAT('%', #{keyword}, '%') </if>"
            + "ORDER BY f.id DESC "
            + "<if test='offset != null and limit != null'> LIMIT #{limit} OFFSET #{offset} </if>"
            + "</script>")
    List<StudentQuestionFavoriteItem> selectFavoriteItemsPaged(@Param("studentId") Long studentId,
                                                               @Param("type") Integer type,
                                                               @Param("keyword") String keyword,
                                                               @Param("offset") Integer offset,
                                                               @Param("limit") Integer limit);

    @Select("<script>"
            + "SELECT COUNT(*) "
            + "FROM student_question_favorites f "
            + "JOIN questions q ON f.question_id = q.id AND q.is_deleted = 0 "
            + "WHERE f.student_id = #{studentId} "
            + "<if test='type != null'> AND q.type = #{type} </if>"
            + "<if test='keyword != null and keyword.trim() != \"\"'> AND q.content LIKE CONCAT('%', #{keyword}, '%') </if>"
            + "</script>")
    long countFavoriteItems(@Param("studentId") Long studentId,
                            @Param("type") Integer type,
                            @Param("keyword") String keyword);
}
