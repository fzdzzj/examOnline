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
import com.exam.common.RequestIdFilter;
import com.exam.common.ResponseCode;
import com.exam.config.AuthProperties;
import com.exam.monitoring.metrics.BusinessMetrics;
import com.exam.service.AuditLogService;
import com.exam.user.entity.Role;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import com.exam.user.service.UserService;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
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
 *
 * <p><b>Redis 签发续期面降级口径（harden-token-store-redis-degradation，runbook 矩阵
 * 「auth:refresh:/auth:sv: 签发与续期面」行的落地）</b>：{@link TokenStoreService} 是无业务语义的
 * Redis 原语层，降级决策（仍发 access / 不返回 refresh / 跳过轮换与下线写）依赖调用语义，故容错
 * 落在本调用侧（try 只包 Redis 调用本身）而非原语层——原语层吞异常会令拦截器对同一原语的既有
 * 降级分支永不可达（降级静默无日志无计数）。口径与限流 fail-open / 拦截器降级放行 / 登录守卫
 * （A1）/ 交卷锁（D1）先例同源：降级放行（或跳过该步）+ ERROR 日志（含 requestId）+ 降级计数
 * （复用 exam.auth.degraded，tag check 追加六个签发续期侧代码常量），不新增第二套降级哲学。
 * 凭据校验（DB BCrypt）与 JWT 签名校验是正确性底座不降级；轮换脚本返回 REUSED/NO_SESSION 是
 * 正常业务结果，照常走既有拒绝分支（降级口只认不可用，不认业务结果）；auth:reset: 找回码族
 * 维持 runbook 硬依赖口径（故障期找回不可用，不冒领为已降级）。
 */
@Slf4j
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 强密码正则：至少包含大小写字母和数字，长度≥8 */
    private static final Pattern STRONG_PASSWORD_PATTERN =
        Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$");

    /** 降级计数 tag：签发侧会话版本读取失败（BusinessMetrics.countAuthDegraded，代码常量有界） */
    static final String DEGRADED_ISSUE_SESSION_VERSION = "issue_session_version";
    /** 降级计数 tag：签发侧 Refresh 会话登记失败（本次不返回 refresh） */
    static final String DEGRADED_ISSUE_REFRESH_STORE = "issue_refresh_store";
    /** 降级计数 tag：续期轮换失败（跳过轮换与复用检测，仅发 access） */
    static final String DEGRADED_REFRESH_ROTATE = "refresh_rotate";
    /** 降级计数 tag：当前 access 的 jti 黑名单写失败（登出/改密共用语义：吊销延迟生效 TTL 自愈） */
    static final String DEGRADED_LOGOUT_BLACKLIST = "logout_blacklist";
    /** 降级计数 tag：登出路径 Refresh 会话删除失败 */
    static final String DEGRADED_LOGOUT_REFRESH_REMOVE = "logout_refresh_remove";
    /** 降级计数 tag：全端下线写失败（改密/踢人/重置密码/刷新侧清理共用语义：延迟生效） */
    static final String DEGRADED_INVALIDATE_SESSIONS = "invalidate_sessions";

    private final UserService userService;
    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final TokenStoreService tokenStore;
    private final LoginGuardService loginGuard;
    private final InviteCodeService inviteCodeService;
    private final MailService mailService;
    private final AuthProperties props;
    private final AuditLogService auditLogService;
    private final BusinessMetrics businessMetrics;

    public AuthService(UserService userService, UserMapper userMapper, JwtUtil jwtUtil, TokenStoreService tokenStore,
                       LoginGuardService loginGuard, InviteCodeService inviteCodeService,
                       MailService mailService, AuthProperties props, AuditLogService auditLogService,
                       BusinessMetrics businessMetrics) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
        this.tokenStore = tokenStore;
        this.loginGuard = loginGuard;
        this.inviteCodeService = inviteCodeService;
        this.mailService = mailService;
        this.props = props;
        this.auditLogService = auditLogService;
        this.businessMetrics = businessMetrics;
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
        try {
            userService.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发同号注册在库层撞 uk_users_username：转友好提示而非 500（事务整体回滚）
            log.warn("注册并发同号撞唯一约束, username={}", req.getUsername());
            throw new BusinessException(ResponseCode.ACCOUNT_ALREADY_EXISTS);
        }

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
            loginGuard.recordFailure(username, ip);
            auditLogService.logLoginEvent(user == null ? null : user.getId(),
                    username, false, ip, "账号或密码错误");
            throw new BusinessException(ResponseCode.ACCOUNT_OR_PASSWORD_ERROR);
        }
        if (user.getStatus() != null && user.getStatus() == 1) {
            throw new BusinessException(ResponseCode.ACCOUNT_DISABLED);
        }
        loginGuard.clearFailures(username);
        auditLogService.logLoginEvent(user.getId(), username, true, ip, "登录成功");
        return issueTokens(user);
    }

    /**
     * 签发双 Token：登记 Refresh 会话（单会话模型：新登录顶掉旧 Refresh）。
     *
     * <p>降级口径（harden-token-store-redis-degradation）：会话版本读取失败 → 以 0 嵌入仍发 access
     * （拦截器侧版本检查已降级放行；真实版本 >0 的用户恢复后新 token 被拒 → 重新登录自愈）；
     * Refresh 会话登记失败 → 本次不返回 refresh（access 周期内可用，静默续期暂缺，到点重新登录自愈）。
     */
    private TokenResponse issueTokens(User user) {
        LoginUser lu = userService.buildLoginUser(user);
        lu.setSessionVersion(issueSessionVersionSafe(user));

        String accessToken = jwtUtil.generateAccessToken(lu);
        String refreshJti = jwtUtil.newJti();
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), refreshJti);
        if (!storeRefreshSafe(user.getId(), refreshJti)) {
            // 登记失败：本次不返回 refresh（响应缺 refreshToken 字段），客户端凭 access 工作
            refreshToken = null;
        }

        log.info("用户 {} 登录成功，签发双 Token", user.getUsername());
        return TokenResponse.of(accessToken, refreshToken,
                jwtUtil.getAccessTtlSeconds(), refreshToken == null ? 0L : jwtUtil.getRefreshTtlSeconds());
    }

    // ==================== 刷新（轮换 + 复用检测） ====================

    /**
     * 刷新：校验 Refresh → 原子轮换（旧 jti 作废）→ 签发新双 Token。
     * 复用检测：已轮换作废的 Refresh 再次提交 → 全端下线（会话版本递增 + 删除会话）。
     *
     * <p>降级口径（harden-token-store-redis-degradation）：轮换因 Redis 异常失败 → 跳过轮换与复用检测，
     * 凭签名 + DB 账号状态校验签发 access-only（不返回新 refresh，客户端保留旧 refresh）——轮换/复用检测
     * 是「会话唯一性的加速器」，签名校验才是底座，与拦截器黑名单降级同构；已轮换/已吊销 refresh 的
     * 延迟裁决半径受 refresh TTL 与故障期时长自限，恢复后首次刷新即按存量 jti 正常裁决。
     * 轮换脚本返回 REUSED/NO_SESSION 是正常业务结果，照常走既有拒绝分支（降级口只认不可用不认业务结果）。
     */
    public TokenResponse refresh(String refreshToken) {
        JwtUtil.RefreshClaims claims;
        try {
            claims = jwtUtil.parseRefreshToken(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }

        String newRefreshJti = jwtUtil.newJti();
        TokenStoreService.RotateResult result;
        boolean rotateDegraded = false;
        try {
            result = tokenStore.rotateRefresh(
                    claims.userId(), claims.jti(), newRefreshJti, jwtUtil.getRefreshTtlSeconds());
        } catch (Exception e) {
            log.error("令牌续期降级：Redis Refresh 轮换失败，跳过轮换仅发 access, userId={}, 异常={}, requestId={}",
                    claims.userId(), e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countAuthDegraded(DEGRADED_REFRESH_ROTATE);
            rotateDegraded = true;
            // 占位值（下方 rotateDegraded 分支先行 return，不会消费该值；仅为明确赋值）
            result = TokenStoreService.RotateResult.NO_SESSION;
        }

        if (rotateDegraded) {
            User user = userService.getById(claims.userId());
            if (user == null || (user.getStatus() != null && user.getStatus() == 1)) {
                // 账号已删/禁用：拒绝语义由 DB 状态保证；Redis 故障期清理写入同样不可用，跳过避免重复降级噪音
                throw new BusinessException(ResponseCode.TOKEN_INVALID);
            }
            LoginUser lu = userService.buildLoginUser(user);
            lu.setSessionVersion(issueSessionVersionSafe(user));
            String accessToken = jwtUtil.generateAccessToken(lu);
            log.info("用户 {} 刷新成功（轮换降级：仅发 access，复用检测延迟裁决）", user.getUsername());
            return TokenResponse.of(accessToken, null, jwtUtil.getAccessTtlSeconds(), 0L);
        }

        switch (result) {
            case OK -> {
                User user = userService.getById(claims.userId());
                if (user == null || (user.getStatus() != null && user.getStatus() == 1)) {
                    removeRefreshSafe(claims.userId(), DEGRADED_INVALIDATE_SESSIONS);
                    throw new BusinessException(ResponseCode.TOKEN_INVALID);
                }
                LoginUser lu = userService.buildLoginUser(user);
                lu.setSessionVersion(issueSessionVersionSafe(user));
                String accessToken = jwtUtil.generateAccessToken(lu);
                String refreshToken2 = jwtUtil.generateRefreshToken(user.getId(), newRefreshJti);
                log.info("用户 {} 刷新成功（轮换 Refresh）", user.getUsername());
                return TokenResponse.of(accessToken, refreshToken2,
                        jwtUtil.getAccessTtlSeconds(), jwtUtil.getRefreshTtlSeconds());
            }
            case REUSED -> {
                // 复用检测：旧 Refresh 被再次使用，判定凭证泄露，全端下线
                log.warn("Refresh 复用检测触发，全端下线: userId={}, jti={}", claims.userId(), claims.jti());
                bumpSessionVersionSafe(claims.userId());
                removeRefreshSafe(claims.userId(), DEGRADED_INVALIDATE_SESSIONS);
                throw new BusinessException(ResponseCode.TOKEN_INVALID);
            }
            default -> throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
    }

    // ==================== 登出 ====================

    /**
     * 登出：当前 Access Token 的 jti 入黑名单（剩余有效期），并删除 Refresh 会话。
     *
     * <p>降级口径（harden-token-store-redis-degradation）：黑名单写失败 → 吊销延迟生效
     * （旧 access 至多再活剩余寿命 ≤30min，与拦截器降级半径同源自限）；Refresh 会话删除失败 →
     * 旧 refresh 至 refresh TTL（7d）自然过期自愈。登出主流程照常返回成功。
     */
    public void logout(String accessToken) {
        LoginUser user = jwtUtil.parseAccessToken(accessToken);
        blacklistSafe(user.getJti(), jwtUtil.getRemainingTtlSeconds(accessToken), user.getUsername());
        removeRefreshSafe(user.getId(), DEGRADED_LOGOUT_REFRESH_REMOVE);
        log.info("用户 {} 登出，Access Token 已入黑名单", user.getUsername());
    }

    // ==================== 修改密码 / 找回密码 ====================

    /**
     * 修改密码：校验原密码 → 更新哈希 → 解除强制改密标记 → 全端下线（会话版本递增 + 当前 jti 入黑名单 + 删 Refresh 会话）。
     *
     * <p>「更新哈希 + 解除强制改密标记」两处 DB 写同处一个事务（rollbackFor=Exception）：
     * 第二步失败则整体回滚，绝不出「密码已改而强制改密流未解除」的中间态；Redis 会话失效操作
     * 在事务内落库步骤之后执行——DB 回滚时尚未产生会话失效副作用，顺序天然安全。
     *
     * @param accessToken 当前请求的 Access Token（用于把其 jti 写入黑名单）
     */
    @Transactional(rollbackFor = Exception.class)
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
        blacklistSafe(operator.getJti(), jwtUtil.getRemainingTtlSeconds(accessToken), operator.getUsername());
        invalidateAllSessions(user.getId());
        log.info("用户 {} 修改密码，旧会话已全部失效", user.getUsername());
    }

    /**
     * 发送找回密码验证码：入口最先做 邮箱+IP 维度固定窗口限流（先于邮箱存在性查询，
     * 防枚举语义不变：未注册邮箱同样计数且同样不暴露存在性）；SMTP 未配置时日志兜底。
     * 邮件文案的有效期取自配置 ttlMinutes（随调用方传入，文案与配置不漂移）。
     */
    public void sendResetCode(String email, String ip) {
        loginGuard.checkCodeRateLimit(email, ip);
        User user = userService.getByEmail(email);
        if (user == null) {
            log.warn("找回密码：邮箱未注册（不向调用方暴露）, email={}", email);
            return;
        }
        String code = generateResetCode();
        int ttlMinutes = props.getResetCode().getTtlMinutes();
        tokenStore.storeResetCode(email, code, Duration.ofMinutes(ttlMinutes));
        mailService.sendResetCode(email, code, ttlMinutes);
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

    /**
     * 全端下线：会话版本递增（旧 Access 全失效）+ 删除 Refresh 会话。
     *
     * <p>降级口径（harden-token-store-redis-degradation）：写入失败 → 跳过下线，改密/重置/踢人主流程
     * 照常成功——bump 未持久化，恢复后旧 access 的 sv 与真实版本一致仍有效至自然过期（≤30min），
     * 旧 refresh 仍为存量会话可继续续期至 refresh TTL（7d）：延迟生效半径受 TTL 自限，恢复后管理员
     * 可重新踢人（届时 Redis 正常、bump 生效）补救。半径如实登记于 runbook 矩阵。
     */
    private void invalidateAllSessions(Long userId) {
        bumpSessionVersionSafe(userId);
        removeRefreshSafe(userId, DEGRADED_INVALIDATE_SESSIONS);
    }

    // ==================== Redis 降级包装（harden-token-store-redis-degradation） ====================

    /** 签发侧读取当前会话版本：Redis 异常降级为 0 仍发 access（半径与自愈见 issueTokens javadoc）。 */
    private long issueSessionVersionSafe(User user) {
        try {
            return tokenStore.getSessionVersion(user.getId());
        } catch (Exception e) {
            log.error("令牌签发降级：Redis 会话版本读取失败，降级以版本 0 签发, username={}, 异常={}, requestId={}",
                    user.getUsername(), e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countAuthDegraded(DEGRADED_ISSUE_SESSION_VERSION);
            return 0L;
        }
    }

    /** 签发侧登记 Refresh 会话：Redis 异常降级为不登记（返回 false → 本次不返回 refresh）。 */
    private boolean storeRefreshSafe(Long userId, String refreshJti) {
        try {
            tokenStore.storeRefresh(userId, refreshJti, jwtUtil.getRefreshTtlSeconds());
            return true;
        } catch (Exception e) {
            log.error("令牌签发降级：Redis Refresh 会话登记失败，本次不返回 refresh, userId={}, 异常={}, requestId={}",
                    userId, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countAuthDegraded(DEGRADED_ISSUE_REFRESH_STORE);
            return false;
        }
    }

    /** 当前 access 的 jti 黑名单写：Redis 异常降级为跳过（吊销延迟生效，受剩余 TTL ≤30min 自限）。 */
    private void blacklistSafe(String jti, long ttlSeconds, String username) {
        try {
            tokenStore.blacklistAccessToken(jti, ttlSeconds);
        } catch (Exception e) {
            log.error("会话吊销降级：Redis 黑名单写入失败，吊销延迟生效（受剩余 TTL 自限）, username={}, 异常={}, requestId={}",
                    username, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countAuthDegraded(DEGRADED_LOGOUT_BLACKLIST);
        }
    }

    /** 会话版本递增：Redis 异常降级为跳过（全端下线延迟生效，半径见 invalidateAllSessions javadoc）。 */
    private void bumpSessionVersionSafe(Long userId) {
        try {
            tokenStore.bumpSessionVersion(userId);
        } catch (Exception e) {
            log.error("全端下线降级：Redis 会话版本递增失败，下线延迟生效, userId={}, 异常={}, requestId={}",
                    userId, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countAuthDegraded(DEGRADED_INVALIDATE_SESSIONS);
        }
    }

    /** Refresh 会话删除：Redis 异常降级为跳过（check 区分登出/全端下线语义，恢复后重试或 TTL 过期自愈）。 */
    private void removeRefreshSafe(Long userId, String check) {
        try {
            tokenStore.removeRefresh(userId);
        } catch (Exception e) {
            log.error("会话清理降级：Redis Refresh 会话删除失败（跳过，恢复后重试或 TTL 过期自愈）, userId={}, 异常={}, requestId={}",
                    userId, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countAuthDegraded(check);
        }
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
