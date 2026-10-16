package com.exam.auth.service;

import com.exam.auth.dto.RegisterRequest;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.AuthProperties;
import com.exam.service.AuditLogService;
import com.exam.user.entity.User;
import com.exam.user.mapper.UserMapper;
import com.exam.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 单元测试（harden-auth-edge-cases）：
 * <ul>
 *   <li>注册并发同号撞唯一约束转友好「账号已存在」（不再呈现 500）；</li>
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
    private AuthService authService;

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
        authService = new AuthService(userService, userMapper, jwtUtil, tokenStore,
                loginGuard, inviteCodeService, mailService, props, auditLogService);
    }

    // ==================== 注册：并发同号竞态友好提示 ====================

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

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.register(req));
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
}
