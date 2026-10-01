package com.exam.clazz.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 学生入班请求。 */
@Data
public class JoinStudentRequest {

    @NotNull(message = "学生用户 ID 不能为空")
    private Long userId;
}
