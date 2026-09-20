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
import com.exam.audit.dto.AuditLogResponse;
import com.exam.common.ApiResponse;
import com.exam.service.AuditLogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员接口（类级角色 + 方法级权限点双重控制）：
 * <ul>
 *   <li>类级 {@code @RequireRole(ADMIN)}：仅 ADMIN 角色可访问（角色层级校验）；</li>
 *   <li>方法级 {@code @RequirePermission}：权限点校验（invite:manage / user:manage）。</li>
 * </ul>
 * 能力：邀请码管理（生成 / 作废 / 列表）、踢人（目标用户全端下线）、安全审计查询。
 */
@RestController
@RequestMapping("/api/admin")
// 见 ExamController 同款说明：audit-logs 的 @Min/@Max 要有它才映射成 400，否则 500
@Validated
@RequireRole(RoleHierarchy.ADMIN)
public class AdminController {

    private final InviteCodeService inviteCodeService;
    private final AuthService authService;
    private final AuditLogService auditLogService;

    public AdminController(InviteCodeService inviteCodeService, AuthService authService,
                           AuditLogService auditLogService) {
        this.inviteCodeService = inviteCodeService;
        this.authService = authService;
        this.auditLogService = auditLogService;
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

    /**
     * 安全审计日志分页：登录成功/失败、账户锁定等事件，按时间倒序。
     *
     * <p>刻意不再标注 {@code @RequirePermission}：审计记录的是"谁在尝试进入系统"，属于 ADMIN
     * 独有的全局视图，类级 {@code @RequireRole(ADMIN)} 已是不可放宽的门槛。若再补一个
     * {@code audit:read} 权限点，它只能挂在 ADMIN 名下（其他角色根本进不到这个方法），
     * 等于把同一道门建两遍，还多一处会漏配的初始化数据。
     */
    @GetMapping("/audit-logs")
    public ApiResponse<List<AuditLogResponse>> auditLogs(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action) {
        return ApiResponse.success(auditLogService.page(page, size, username, action));
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
