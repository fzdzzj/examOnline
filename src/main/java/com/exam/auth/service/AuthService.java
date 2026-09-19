package com.exam.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.auth.dto.ChangePasswordRequest;
import com.exam.auth.dto.CurrentUserResponse;
import com.exam.auth.dto.RegisterRequest;
import com.exam.auth.dto.ResetPasswordRequest;
import com.exam.auth.dto.RoleType;
import com.exam.auth.dto.TokenResponse;
import com.exam.auth.entity.InviteCode;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.AuthProperties;
import com.exam.service.AuditLogService;
import com.exam.user.entity.Role;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import com.exam.user.service.UserService;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 认证核心服务：注册、登录（双 Token 签发）、刷新（轮换 + 复用检测）、登出（黑名单）、
 * 改密码/找回密码（旧会话全失效）、当前用户信息。
 *
 * <p>安全要点：
 * <ul>
 *   <li>密码 BCrypt 不可逆哈希存储，登录统一"账号或密码错误"提示防账号枚举；</li>
 *   <li>Refresh 原子轮换（Lua），旧 Refresh 复用即全端下线（会话版本递增）；</li>
 *   <li>登出/改密码/踢人写入 jti 黑名单 + 会话版本递增，Access 立即失效。</li>
 * </ul>
 */
@Slf4j
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    
    /** 强密码正则：至少包含大小写字母和数字，长度≥8 */
    private static final Pattern STRONG_PASSWORD_PATTERN = 
        Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");

    private final UserService userService;
    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final TokenStoreService tokenStore;
    private final LoginGuardService loginGuard;
    private final InviteCodeService inviteCodeService;
    private final MailService mailService;
    private final AuthProperties props;
    private final AuditLogService auditLogService;

    public AuthService(UserService userService, UserMapper userMapper, JwtUtil jwtUtil, TokenStoreService tokenStore,
                       LoginGuardService loginGuard, InviteCodeService inviteCodeService,
                       MailService mailService, AuthProperties props, AuditLogService auditLogService) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.tokenStore = tokenStore;
        this.loginGuard = loginGuard;
        this.inviteCodeService = inviteCodeService;
        this.mailService = mailService;
        this.props = props;
        this.auditLogService = auditLogService;
    }

    // ==================== 注册 ====================

    /**
     * 注册：学生以学号自助注册（默认 STUDENT）；教师以工号 + 有效邀请码注册（授予 TEACHER）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest req) {
        RoleType roleType;
        try {
            roleType = RoleType.from(req.getRoleType());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "注册身份必须是 STUDENT 或 TEACHER");
        }

        if (userService.existsByUsername(req.getUsername())) {
            throw new BusinessException(ResponseCode.ACCOUNT_ALREADY_EXISTS);
        }

        // 密码复杂度校验
        validatePasswordStrength(req.getPassword());

        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(BCrypt.hashpw(req.getPassword(), BCrypt.gensalt(12)));
        user.setName(req.getName());
        user.setEmail(req.getEmail());
        user.setStatus(0);
        user.setMustChangePassword(0);
        userService.insert(user);

        if (roleType == RoleType.STUDENT) {
            bindRole(user, RoleHierarchy.STUDENT);
            log.info("学生注册成功: username={}", req.getUsername());
            return;
        }

        // 教师：校验邀请码，有效才授予 TEACHER
        InviteCode inviteCode = inviteCodeService.getValidByCode(req.getInviteCode());
        if (inviteCode == null) {
            throw new BusinessException(ResponseCode.INVITE_CODE_INVALID);
        }
        bindRole(user, RoleHierarchy.TEACHER);
        inviteCodeService.markUsed(inviteCode);
        log.info("教师注册成功: username={}, inviteCode={}", req.getUsername(), inviteCode.getCode());
    }

    private void bindRole(User user, String roleCode) {
        Role role = userService.getRoleByCode(roleCode);
        if (role == null) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "角色未初始化: " + roleCode);
        }
        userService.bindRole(user.getId(), role.getId());
    }

    // ==================== 登录 ====================

    /**
     * 登录：限流 → 锁定检查 → 校验凭据（统一提示防枚举）→ 签发双 Token。
     */
    public TokenResponse login(String username, String password, String ip) {
        loginGuard.checkRateLimit(username, ip);
        loginGuard.checkLocked(username);

        User user = userService.getByUsername(username);
        // 统一失败提示：账号不存在与密码错误返回同一条消息，防账号枚举
        if (user == null || !BCrypt.checkpw(password, user.getPassword())) {
            loginGuard.recordFailure(username);
            auditLogService.logLoginEvent(username, false, ip, "账号或密码错误");
            throw new BusinessException(ResponseCode.ACCOUNT_OR_PASSWORD_ERROR);
        }
        if (user.getStatus() != null && user.getStatus() == 1) {
            throw new BusinessException(ResponseCode.ACCOUNT_DISABLED);
        }
        loginGuard.clearFailures(username);
        auditLogService.logLoginEvent(username, true, ip, "登录成功");
        return issueTokens(user);
    }

    /** 签发双 Token：登记 Refresh 会话（单会话模型：新登录顶掉旧 Refresh）。 */
    private TokenResponse issueTokens(User user) {
        LoginUser lu = userService.buildLoginUser(user);
        lu.setSessionVersion(tokenStore.getSessionVersion(user.getId()));

        String accessToken = jwtUtil.generateAccessToken(lu);
        String refreshJti = jwtUtil.newJti();
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), refreshJti);
        tokenStore.storeRefresh(user.getId(), refreshJti, jwtUtil.getRefreshTtlSeconds());

        log.info("用户 {} 登录成功，签发双 Token", user.getUsername());
        return TokenResponse.of(accessToken, refreshToken,
                jwtUtil.getAccessTtlSeconds(), jwtUtil.getRefreshTtlSeconds());
    }

    // ==================== 刷新（轮换 + 复用检测） ====================

    /**
     * 刷新：校验 Refresh → 原子轮换（旧 jti 作废）→ 签发新双 Token。
     * 复用检测：已轮换作废的 Refresh 再次提交 → 全端下线（会话版本递增 + 删除会话）。
     */
    public TokenResponse refresh(String refreshToken) {
        JwtUtil.RefreshClaims claims;
        try {
            claims = jwtUtil.parseRefreshToken(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }

        String newRefreshJti = jwtUtil.newJti();
        TokenStoreService.RotateResult result = tokenStore.rotateRefresh(
                claims.userId(), claims.jti(), newRefreshJti, jwtUtil.getRefreshTtlSeconds());

        switch (result) {
            case OK -> {
                User user = userService.getById(claims.userId());
                if (user == null || (user.getStatus() != null && user.getStatus() == 1)) {
                    tokenStore.removeRefresh(claims.userId());
                    throw new BusinessException(ResponseCode.TOKEN_INVALID);
                }
                LoginUser lu = userService.buildLoginUser(user);
                lu.setSessionVersion(tokenStore.getSessionVersion(user.getId()));
                String accessToken = jwtUtil.generateAccessToken(lu);
                String refreshToken2 = jwtUtil.generateRefreshToken(user.getId(), newRefreshJti);
                log.info("用户 {} 刷新成功（轮换 Refresh）", user.getUsername());
                return TokenResponse.of(accessToken, refreshToken2,
                        jwtUtil.getAccessTtlSeconds(), jwtUtil.getRefreshTtlSeconds());
            }
            case REUSED -> {
                // 复用检测：旧 Refresh 被再次使用，判定凭证泄露，全端下线
                log.warn("Refresh 复用检测触发，全端下线: userId={}, jti={}", claims.userId(), claims.jti());
                tokenStore.bumpSessionVersion(claims.userId());
                tokenStore.removeRefresh(claims.userId());
                throw new BusinessException(ResponseCode.TOKEN_INVALID);
            }
            default -> throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
    }

    // ==================== 登出 ====================

    /**
     * 登出：当前 Access Token 的 jti 入黑名单（剩余有效期），并删除 Refresh 会话。
     */
    public void logout(String accessToken) {
        LoginUser user = jwtUtil.parseAccessToken(accessToken);
        tokenStore.blacklistAccessToken(user.getJti(), jwtUtil.getRemainingTtlSeconds(accessToken));
        tokenStore.removeRefresh(user.getId());
        log.info("用户 {} 登出，Access Token 已入黑名单", user.getUsername());
    }

    // ==================== 修改密码 / 找回密码 ====================

    /**
     * 修改密码：校验原密码 → 更新哈希 → 解除强制改密标记 → 全端下线（会话版本递增 + 当前 jti 入黑名单 + 删 Refresh 会话）。
     *
     * @param accessToken 当前请求的 Access Token（用于把其 jti 写入黑名单）
     */
    public void changePassword(ChangePasswordRequest req, LoginUser operator, String accessToken) {
        User user = userService.getById(operator.getId());
        if (!BCrypt.checkpw(req.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ResponseCode.OLD_PASSWORD_ERROR);
        }
        validatePasswordStrength(req.getNewPassword());
        userService.updatePassword(user.getId(), BCrypt.hashpw(req.getNewPassword(), BCrypt.gensalt(12)));
        // 密码已不再是初始密码 → 解除强制改密标记（紧接改密成功之后、会话失效之前落库）。
        // 直接走 UserMapper 定向更新：UserService 未暴露该字段的更新方法，跨模块注入 UserMapper 与既有惯例一致。
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId())
                .set(User::getMustChangePassword, 0));
        tokenStore.blacklistAccessToken(operator.getJti(), jwtUtil.getRemainingTtlSeconds(accessToken));
        invalidateAllSessions(user.getId());
        log.info("用户 {} 修改密码，旧会话已全部失效", user.getUsername());
    }

    /**
     * 发送找回密码验证码：邮箱未注册不暴露（统一返回成功），SMTP 未配置时日志兜底。
     */
    public void sendResetCode(String email) {
        User user = userService.getByEmail(email);
        if (user == null) {
            log.warn("找回密码：邮箱未注册（不向调用方暴露）, email={}", email);
            return;
        }
        String code = generateResetCode();
        tokenStore.storeResetCode(email, code,
                Duration.ofMinutes(props.getResetCode().getTtlMinutes()));
        mailService.sendResetCode(email, code);
    }

    /**
     * 重置密码：单次验证码校验（GETDEL 消费）→ 更新密码 → 全端下线。
     */
    public void resetPassword(ResetPasswordRequest req) {
        // 先消费验证码（单次使用）；错误/过期与邮箱未注册统一提示防枚举
        String stored = tokenStore.consumeResetCode(req.getEmail());
        if (stored == null || !stored.equals(req.getCode())) {
            throw new BusinessException(ResponseCode.RESET_CODE_INVALID);
        }
        User user = userService.getByEmail(req.getEmail());
        if (user == null) {
            throw new BusinessException(ResponseCode.RESET_CODE_INVALID);
        }
        validatePasswordStrength(req.getNewPassword());
        userService.updatePassword(user.getId(), BCrypt.hashpw(req.getNewPassword(), BCrypt.gensalt(12)));
        invalidateAllSessions(user.getId());
        log.info("用户 {} 通过验证码重置密码成功", user.getUsername());
    }

    // ==================== 管理员：踢人 ====================

    /** 管理员踢人：目标用户所有会话立即失效（全端下线）。 */
    public void kickUser(Long targetUserId) {
        invalidateAllSessions(targetUserId);
        log.info("管理员踢人: userId={}，全部会话已失效", targetUserId);
    }

    // ==================== 当前用户 ====================

    public CurrentUserResponse currentUser() {
        LoginUser u = SecurityUtil.getCurrentUser();
        if (u == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        // 强制改密标记实时读库（不落 JWT claim：可变状态入无状态 token 会产生「已改密仍被要求改密」的窗口）。
        // 账号已被删除等查不到情形按「不强制改密」处理，不抛异常。
        User user = userService.getById(u.getId());
        Integer flag = user == null ? null : user.getMustChangePassword();
        boolean mustChangePassword = flag != null && flag != 0;
        return new CurrentUserResponse(u.getId(), u.getUsername(), u.getName(), u.getEmail(),
                u.getRoles(), u.getPermissions(), mustChangePassword);
    }

    // ==================== 私有 ====================

    /** 全端下线：会话版本递增（旧 Access 全失效）+ 删除 Refresh 会话。 */
    private void invalidateAllSessions(Long userId) {
        tokenStore.bumpSessionVersion(userId);
        tokenStore.removeRefresh(userId);
    }

    /** 密码复杂度校验：至少包含大小写字母和数字，长度≥8 */
    private void validatePasswordStrength(String password) {
        if (!STRONG_PASSWORD_PATTERN.matcher(password).matches()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                "密码需包含大小写字母和数字，长度至少 8 位");
        }
    }

    private String generateResetCode() {
        int length = props.getResetCode().getLength();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}
