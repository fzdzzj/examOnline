package com.exam.auth.controller;

import com.exam.auth.dto.ChangePasswordRequest;
import com.exam.auth.dto.CurrentUserResponse;
import com.exam.auth.dto.LoginRequest;
import com.exam.auth.dto.RefreshRequest;
import com.exam.auth.dto.RegisterRequest;
import com.exam.auth.dto.ResetPasswordRequest;
import com.exam.auth.dto.SendResetCodeRequest;
import com.exam.auth.dto.TokenResponse;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.auth.service.AuthService;
import com.exam.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：
 * <ul>
 *   <li>公开：注册 / 登录 / 刷新 / 找回密码（发送验证码、重置）；</li>
 *   <li>需登录：登出 / 修改密码 / 当前用户信息。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 注册（学生自助 / 教师 + 邀请码） */
    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ApiResponse.success();
    }

    /** 登录：签发双 Token */
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        return ApiResponse.success(authService.login(request.getUsername(), request.getPassword(), ip));
    }

    /** 刷新：Refresh 轮换 + 复用检测，返回新双 Token */
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.success(authService.refresh(request.getRefreshToken()));
    }

    /** 登出：当前 Access Token 入黑名单 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest http) {
        authService.logout(extractBearerToken(http));
        return ApiResponse.success();
    }

    /** 修改密码（成功后旧会话全失效，需重新登录） */
    @PostMapping("/password/change")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                            HttpServletRequest http) {
        LoginUser operator = SecurityUtil.getCurrentUser();
        authService.changePassword(request, operator, extractBearerToken(http));
        return ApiResponse.success();
    }

    /** 发送找回密码验证码（防枚举：邮箱未注册也返回成功） */
    @PostMapping("/password/reset-code")
    public ApiResponse<Void> sendResetCode(@Valid @RequestBody SendResetCodeRequest request) {
        authService.sendResetCode(request.getEmail());
        return ApiResponse.success();
    }

    /** 校验验证码并重置密码（成功后旧会话全失效） */
    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.success();
    }

    /** 当前登录用户信息 */
    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me() {
        return ApiResponse.success(authService.currentUser());
    }

    private String extractBearerToken(HttpServletRequest http) {
        String header = http.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return "";
    }
}
