package com.exam.auth.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.auth.dto.ChangePasswordRequest;
import com.exam.auth.dto.RegisterRequest;
import com.exam.auth.dto.TokenResponse;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.AuthProperties;
import com.exam.monitoring.metrics.BusinessMetrics;
import com.exam.service.AuditLogService;
import com.exam.user.entity.Role;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import com.exam.user.service.UserService;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 单元测试（harden-auth-edge-cases）：
 * <ul>
 *   <li>注册并发同号撞唯一约束转友好「账号已存在」（不再呈现 500）；</li>
 *   <li>注册入口首行做 客户端 IP 维度频控（harden-register-rate-limit，先于身份分支与存在性查询）；</li>
 *   <li>changePassword 必须声明 @Transactional(rollbackFor=Exception.class)（两 DB 写原子）；</li>
 *   <li>sendResetCode 入口先做 email+IP 维度限流（先于邮箱存在性查询，防枚举语义不变），
 *       并把配置的 ttlMinutes 透传给邮件服务。</li>
 * </ul>
 */
class AuthServiceTest {

    private UserService userService;
    private UserMapper userMapper;
    private JwtUtil jwtUtil;
    private TokenStoreService tokenStore;
    private LoginGuardService loginGuard;
    private InviteCodeService inviteCodeService;
    private MailService mailService;
    private AuthProperties props;
    private AuditLogService auditLogService;
    private BusinessMetrics businessMetrics;
    private AuthService authService;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        userMapper = mock(UserMapper.class);
        jwtUtil = mock(JwtUtil.class);
        tokenStore = mock(TokenStoreService.class);
        loginGuard = mock(LoginGuardService.class);
        inviteCodeService = mock(InviteCodeService.class);
        mailService = mock(MailService.class);
        props = new AuthProperties();
        auditLogService = mock(AuditLogService.class);
        businessMetrics = mock(BusinessMetrics.class);
        authService = new AuthService(userService, userMapper, jwtUtil, tokenStore,
                loginGuard, inviteCodeService, mailService, props, auditLogService, businessMetrics);

        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(AuthService.class)).addAppender(appender);

        // changePassword 的 lambdaUpdate 依赖 MyBatis-Plus 实体元数据缓存（纯单测无运行时初始化），
        // 手工注册 User 实体（InviteCodeServiceTest 同款先例）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    // ==================== 注册：IP 频控前置 + 并发同号竞态友好提示 ====================

    @Test
    void registerRunsIpRateLimitBeforeAnyOtherLogic() {
        when(userService.existsByUsername("stu_rl_pre")).thenReturn(false);
        Role role = new Role();
        role.setId(1L);
        when(userService.getRoleByCode(RoleHierarchy.STUDENT)).thenReturn(role);

        RegisterRequest req = new RegisterRequest();
        req.setUsername("stu_rl_pre");
        req.setPassword("Pass1234");
        req.setName("前置限流");
        req.setRoleType("STUDENT");

        authService.register(req, "203.0.113.8");

        // 频控必须首行前置（先于身份分支与用户名存在性查询），BCrypt 与入库之前拦截
        InOrder inOrder = inOrder(loginGuard, userService);
        inOrder.verify(loginGuard).checkRegisterRateLimit("203.0.113.8");
        inOrder.verify(userService).existsByUsername("stu_rl_pre");
    }

    @Test
    void registerConvertsDuplicateKeyExceptionToFriendlyAccountExists() {
        when(userService.existsByUsername("stu_dup")).thenReturn(false);
        doThrow(new DuplicateKeyException("uk_users_username"))
                .when(userService).insert(any(User.class));

        RegisterRequest req = new RegisterRequest();
        req.setUsername("stu_dup");
        req.setPassword("Pass1234");
        req.setName("并发注册");
        req.setRoleType("STUDENT");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(req, "203.0.113.5"));
        assertEquals(ResponseCode.ACCOUNT_ALREADY_EXISTS.getCode(), ex.getCode());
    }

    // ==================== 修改密码：事务原子性 ====================

    @Test
    void changePasswordIsTransactionalWithRollbackFor() throws Exception {
        Transactional tx = Arrays.stream(AuthService.class.getMethods())
                .filter(m -> m.getName().equals("changePassword"))
                .findFirst()
                .orElseThrow()
                .getAnnotation(Transactional.class);
        assertNotNull(tx, "changePassword 必须声明 @Transactional（两处 DB 写须同事务）");
        assertEquals(Exception.class, tx.rollbackFor()[0], "事务必须 rollbackFor=Exception.class");
    }

    // ==================== 找回密码验证码：限流先于存在性查询 ====================

    @Test
    void sendResetCodeRunsCodeRateLimitBeforeExistenceQuery() {
        when(userService.getByEmail("ghost@test.com")).thenReturn(null);

        authService.sendResetCode("ghost@test.com", "203.0.113.9");

        InOrder inOrder = inOrder(loginGuard, userService);
        inOrder.verify(loginGuard).checkCodeRateLimit("ghost@test.com", "203.0.113.9");
        inOrder.verify(userService).getByEmail("ghost@test.com");
        // 未注册邮箱不暴露存在性：不发邮件、不写验证码
        verify(mailService, never()).sendResetCode(anyString(), anyString(), anyInt());
        verify(tokenStore, never()).storeResetCode(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void sendResetCodePassesConfiguredTtlToMailService() {
        User user = new User();
        user.setId(1L);
        when(userService.getByEmail("real@test.com")).thenReturn(user);
        props.getResetCode().setTtlMinutes(10);

        authService.sendResetCode("real@test.com", "198.51.100.7");

        verify(loginGuard).checkCodeRateLimit("real@test.com", "198.51.100.7");
        verify(tokenStore).storeResetCode(eq("real@test.com"), anyString(), eq(Duration.ofMinutes(10)));
        verify(mailService).sendResetCode(eq("real@test.com"), anyString(), eq(10));
    }

    // ==================== Redis 签发续期面降级（harden-token-store-redis-degradation） ====================

    private User activeUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("stu01");
        user.setPassword(BCrypt.hashpw("Passw0rdX", BCrypt.gensalt()));
        user.setStatus(0);
        return user;
    }

    private LoginUser loginUserOf(User user) {
        LoginUser lu = new LoginUser();
        lu.setId(user.getId());
        lu.setUsername(user.getUsername());
        lu.setRoleLevel(1);
        return lu;
    }

    private void stubIssuePath(User user) {
        when(userService.buildLoginUser(user)).thenReturn(loginUserOf(user));
        when(jwtUtil.generateAccessToken(any(LoginUser.class))).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(eq(1L), anyString())).thenReturn("refresh-token");
        when(jwtUtil.newJti()).thenReturn("jti-1");
    }

    @Test
    void loginStillIssuesAccessWhenSessionVersionReadFails() {
        User user = activeUser();
        when(userService.getByUsername("stu01")).thenReturn(user);
        stubIssuePath(user);
        when(tokenStore.getSessionVersion(1L)).thenThrow(new RuntimeException("redis down"));

        TokenResponse resp = authService.login("stu01", "Passw0rdX", "203.0.113.1");

        assertNotNull(resp.getAccessToken(), "会话版本读失败必须仍发 access");
        assertNotNull(resp.getRefreshToken(), "Refresh 存储成功时 refresh 照常返回");
        verify(tokenStore).storeRefresh(eq(1L), eq("jti-1"), anyLong());
        verify(businessMetrics).countAuthDegraded("issue_session_version");
        assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("令牌签发降级")),
                "必须落 ERROR 降级日志");
    }

    @Test
    void loginOmitsRefreshWhenStoreRefreshFails() {
        User user = activeUser();
        when(userService.getByUsername("stu01")).thenReturn(user);
        stubIssuePath(user);
        doThrow(new RuntimeException("redis down")).when(tokenStore).storeRefresh(eq(1L), anyString(), anyLong());

        TokenResponse resp = authService.login("stu01", "Passw0rdX", "203.0.113.1");

        assertNotNull(resp.getAccessToken(), "Refresh 存储失败必须仍发 access");
        assertNull(resp.getRefreshToken(), "Refresh 存储失败本次不返回 refresh");
        assertEquals(0L, resp.getRefreshExpiresIn(), "无 refresh 时 refreshExpiresIn 如实为 0");
        verify(businessMetrics).countAuthDegraded("issue_refresh_store");
    }

    @Test
    void loginHappyPathCountsNoDegradation() {
        User user = activeUser();
        when(userService.getByUsername("stu01")).thenReturn(user);
        stubIssuePath(user);
        when(tokenStore.getSessionVersion(1L)).thenReturn(5L);

        TokenResponse resp = authService.login("stu01", "Passw0rdX", "203.0.113.1");

        assertNotNull(resp.getAccessToken());
        assertNotNull(resp.getRefreshToken());
        verify(businessMetrics, never()).countAuthDegraded(anyString());
    }

    @Test
    void refreshReturnsAccessOnlyWhenRotationFails() {
        when(jwtUtil.parseRefreshToken("rt")).thenReturn(new JwtUtil.RefreshClaims(1L, "old-jti"));
        when(tokenStore.rotateRefresh(eq(1L), eq("old-jti"), anyString(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));
        User user = activeUser();
        when(userService.getById(1L)).thenReturn(user);
        stubIssuePath(user);

        TokenResponse resp = authService.refresh("rt");

        assertNotNull(resp.getAccessToken(), "轮换失败必须降级仅发 access");
        assertNull(resp.getRefreshToken(), "轮换失败不返回新 refresh（客户端保留旧 refresh）");
        assertEquals(0L, resp.getRefreshExpiresIn());
        verify(businessMetrics).countAuthDegraded("refresh_rotate");
        assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("令牌续期降级")),
                "必须落 ERROR 降级日志");
    }

    @Test
    void refreshReuseStillRejectsWhenCleanupFails() {
        when(jwtUtil.parseRefreshToken("rt")).thenReturn(new JwtUtil.RefreshClaims(1L, "old-jti"));
        when(jwtUtil.newJti()).thenReturn("new-jti");
        when(tokenStore.rotateRefresh(eq(1L), eq("old-jti"), anyString(), anyLong()))
                .thenReturn(TokenStoreService.RotateResult.REUSED);
        doThrow(new RuntimeException("redis down")).when(tokenStore).bumpSessionVersion(1L);
        doThrow(new RuntimeException("redis down")).when(tokenStore).removeRefresh(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.refresh("rt"));
        assertEquals(ResponseCode.TOKEN_INVALID.getCode(), ex.getCode(),
                "复用判定是正常业务结果，清理降级不改拒绝语义");
        verify(businessMetrics, times(2)).countAuthDegraded("invalidate_sessions");
    }

    @Test
    void logoutCompletesWhenBlacklistWriteFails() {
        LoginUser lu = new LoginUser();
        lu.setId(1L);
        lu.setUsername("stu01");
        lu.setJti("jti-x");
        when(jwtUtil.parseAccessToken("access-token")).thenReturn(lu);
        when(jwtUtil.getRemainingTtlSeconds("access-token")).thenReturn(1200L);
        doThrow(new RuntimeException("redis down")).when(tokenStore).blacklistAccessToken("jti-x", 1200L);

        assertDoesNotThrow(() -> authService.logout("access-token"), "黑名单写失败登出照常成功（吊销延迟生效）");
        verify(tokenStore).removeRefresh(1L);
        verify(businessMetrics).countAuthDegraded("logout_blacklist");
    }

    @Test
    void changePasswordCompletesWhenSessionInvalidationFails() {
        User user = activeUser();
        when(userService.getById(1L)).thenReturn(user);
        LoginUser operator = new LoginUser();
        operator.setId(1L);
        operator.setUsername("stu01");
        operator.setJti("jti-y");
        when(jwtUtil.getRemainingTtlSeconds("access-token")).thenReturn(600L);
        doThrow(new RuntimeException("redis down")).when(tokenStore).bumpSessionVersion(1L);

        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("Passw0rdX");
        req.setNewPassword("NewPass123");

        assertDoesNotThrow(() -> authService.changePassword(req, operator, "access-token"),
                "全端下线写失败改密照常成功（DB 写不受影响、事务不回滚）");
        verify(userService).updatePassword(eq(1L), anyString());
        verify(businessMetrics).countAuthDegraded("invalidate_sessions");
    }
}
