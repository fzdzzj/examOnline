package com.exam.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 双 Token 登录/刷新响应：Access Token（短效，业务鉴权）+ Refresh Token（长效，续期）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {

    /** 访问令牌（默认 30 分钟有效） */
    private String accessToken;

    /** 刷新令牌（默认 7 天有效，刷新时轮换） */
    private String refreshToken;

    /** 令牌类型 */
    private String tokenType;

    /** Access Token 剩余有效秒数 */
    private long accessExpiresIn;

    /** Refresh Token 剩余有效秒数 */
    private long refreshExpiresIn;

    public static TokenResponse of(String accessToken, String refreshToken,
                                   long accessExpiresIn, long refreshExpiresIn) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", accessExpiresIn, refreshExpiresIn);
    }
}
