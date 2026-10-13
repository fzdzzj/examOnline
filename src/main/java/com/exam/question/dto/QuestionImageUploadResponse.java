package com.exam.question.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 题干图片上传响应（add-question-image）：返回相对 URL /images/{uuid}.{ext}，
 * 前端先传图拿 URL，再随题目创建/更新表单提交 imageUrl（上传与题目保存解耦）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuestionImageUploadResponse {

    /** 相对 URL，可直接作为题目 imageUrl 提交 */
    private String url;
}
