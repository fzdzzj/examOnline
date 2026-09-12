package com.exam.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新令牌请求：携带 Refresh Token 换取新的双 Token（旧 Refresh 轮换作废）。
 */
@Data
public class RefreshRequest {

    @NotBlank(message = "刷新令牌不能为空")
    private String refreshToken;
}
