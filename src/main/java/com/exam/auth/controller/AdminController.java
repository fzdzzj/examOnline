package com.exam.auth.controller;

import com.exam.auth.dto.InviteCodeCreateRequest;
import com.exam.auth.dto.InviteCodeResponse;
import com.exam.auth.entity.InviteCode;
import com.exam.auth.security.RequirePermission;
import com.exam.auth.security.RequireRole;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.auth.service.AuthService;
import com.exam.auth.service.InviteCodeService;
import com.exam.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员接口（类级角色 + 方法级权限点双重控制）：
 * <ul>
 *   <li>类级 {@code @RequireRole(ADMIN)}：仅 ADMIN 角色可访问（角色层级校验）；</li>
 *   <li>方法级 {@code @RequirePermission}：权限点校验（invite:manage / user:manage）。</li>
 * </ul>
 * 能力：邀请码管理（生成 / 作废 / 列表）、踢人（目标用户全端下线）。
 */
@RestController
@RequestMapping("/api/admin")
@RequireRole(RoleHierarchy.ADMIN)
public class AdminController {

    private final InviteCodeService inviteCodeService;
    private final AuthService authService;

    public AdminController(InviteCodeService inviteCodeService, AuthService authService) {
        this.inviteCodeService = inviteCodeService;
        this.authService = authService;
    }

    /** 生成教师邀请码 */
    @PostMapping("/invite-codes")
    @RequirePermission("invite:manage")
    public ApiResponse<InviteCodeResponse> createInviteCode(@Valid @RequestBody(required = false)
                                                            InviteCodeCreateRequest request) {
        String note = request == null ? null : request.getNote();
        InviteCode ic = inviteCodeService.create(SecurityUtil.getUserId(), note);
        return ApiResponse.success(toResponse(ic));
    }

    /** 作废邀请码 */
    @PostMapping("/invite-codes/{id}/invalidate")
    @RequirePermission("invite:manage")
    public ApiResponse<Void> invalidateInviteCode(@PathVariable Long id) {
        inviteCodeService.invalidate(id);
        return ApiResponse.success();
    }

    /** 邀请码列表 */
    @GetMapping("/invite-codes")
    @RequirePermission("invite:manage")
    public ApiResponse<List<InviteCodeResponse>> listInviteCodes() {
        return ApiResponse.success(inviteCodeService.listAll().stream()
                .map(this::toResponse)
                .toList());
    }

    /** 踢人：目标用户所有会话立即失效（需重新登录） */
    @PostMapping("/users/{userId}/kick")
    @RequirePermission("user:manage")
    public ApiResponse<Void> kickUser(@PathVariable Long userId) {
        authService.kickUser(userId);
        return ApiResponse.success();
    }

    private InviteCodeResponse toResponse(InviteCode ic) {
        return new InviteCodeResponse(ic.getId(), ic.getCode(), ic.getNote(), ic.getStatus(),
                ic.getUsedCount(), ic.getCreatedBy(), ic.getCreatedTime());
    }
}
