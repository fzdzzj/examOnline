package com.exam.clazz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 创建班级请求。 */
@Data
public class ClassCreateRequest {

    @NotBlank(message = "班级名不能为空")
    @Size(max = 64, message = "班级名最长 64 字")
    private String name;

    /** 课程 ID（可选：课程实体后续阶段提供，先存 ID） */
    private Long courseId;
}
