package com.exam.grading.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 主观题批改行分页信封（add-subjective-grading-pagination）：
 * 行端点响应恒为此形状（不做「缺省裸 List / 带参信封」双形态），rows 为当前页行集。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubjectiveGradePageResponse {

    /** 当前页行集（page/size 缺省时为该题全部行） */
    private List<SubjectiveGradeRow> rows;

    /** 行总数：与 rows 同口径（筛选后、分页前）的计数，前端据此算页数 */
    private int total;

    /** 已批行数：与题级进度（SubjectiveQuestionItem.gradedStudents）同口径，不受行筛选影响 */
    private int graded;
}
