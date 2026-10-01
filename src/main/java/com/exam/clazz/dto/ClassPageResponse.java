package com.exam.clazz.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 班级分页响应（结构对齐题目分页 QuestionPageResponse）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassPageResponse {

    private List<ClassResponse> list;

    private long total;

    private long page;

    private long size;
}
