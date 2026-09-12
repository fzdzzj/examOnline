package com.exam.auth.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员生成教师邀请码请求。
 */
@Data
public class InviteCodeCreateRequest {

    /** 备注（可选，如"2026 春招聘"） */
    @Size(max = 128, message = "备注长度不能超过 128")
    private String note;
}
