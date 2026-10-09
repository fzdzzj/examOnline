package com.exam.score.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 考试错题分组：按考试聚合该场考试中的所有错题。
 */
@Data
public class ExamWrongQuestionsGroup {

    private Long examId;

    private String examTitle;

    private LocalDateTime examTime;

    /** 该场考试的错题列表 */
    private List<WrongQuestionItem> wrongQuestions;
}
