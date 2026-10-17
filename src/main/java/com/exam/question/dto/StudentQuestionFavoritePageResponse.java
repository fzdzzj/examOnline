package com.exam.question.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 收藏题目分页响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentQuestionFavoritePageResponse {

    private List<StudentQuestionFavoriteResponse> list;

    private long total;

    private long page;

    private long size;
}
