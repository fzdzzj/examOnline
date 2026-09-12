package com.exam.question.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 题目分页响应。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuestionPageResponse {

    private List<QuestionResponse> list;

    private long total;

    private long page;

    private long size;
}
