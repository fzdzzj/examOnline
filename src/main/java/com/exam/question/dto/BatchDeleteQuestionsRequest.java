package com.exam.question.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 批量删除题目请求：单次上限与批量入卷同量级（100），防误传全库。 */
@Data
public class BatchDeleteQuestionsRequest {

    @NotEmpty(message = "题目列表不能为空")
    @Size(max = 100, message = "单次批量至多 100 题")
    private List<Long> ids;
}
