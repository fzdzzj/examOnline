package com.exam.question.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 创建标签请求：扁平四类（学科/难度/题型/自定义）。 */
@Data
public class TagCreateRequest {

    @NotBlank(message = "标签名不能为空")
    @Size(max = 64, message = "标签名最长 64 字")
    private String name;

    /** 标签类型码 */
    @NotBlank(message = "标签类型不能为空")
    @Pattern(regexp = "SUBJECT|DIFFICULTY|QUESTION_TYPE|CUSTOM", message = "非法标签类型")
    private String type;
}
