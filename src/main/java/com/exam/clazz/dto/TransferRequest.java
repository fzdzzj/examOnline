package com.exam.clazz.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 转班请求：目标班级 ID。 */
@Data
public class TransferRequest {

    @NotNull(message = "目标班级 ID 不能为空")
    private Long targetClassId;
}
