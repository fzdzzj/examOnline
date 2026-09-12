package com.exam.auth;

import com.exam.auth.security.LoginUser;
import com.exam.auth.service.JwtUtil;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JwtUtil 单元测试：签发/解析、篡改拒绝、过期拒绝、类型隔离、剩余有效期。
 * 不依赖 Spring 容器（直接构造，秒级短有效期验证过期）。
 */
class JwtUtilTest {

    private static final String SECRET = "test-only-jwt-secret-0123456789-abcdefghijklmnopqrstuvwxyz";

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        // access 60s / refresh 60s：便于验证过期
        jwtUtil = new JwtUtil(SECRET, 60, 60);
    }

    private LoginUser sampleUser() {
        LoginUser user = new LoginUser();
        user.setId(100L);
        user.setUsername("20260001");
        user.setName("张三");
        user.setEmail("stu@test.com");
        user.setRoles(new LinkedHashSet<>(Set.of("STUDENT")));
        user.setPermissions(new LinkedHashSet<>(Set.of("exam:take", "score:view")));
        user.setRoleLevel(1);
        user.setSessionVersion(0);
        return user;
    }

    @Test
    void accessTokenRoundTripPreservesClaims() {
        LoginUser user = sampleUser();
        String token = jwtUtil.generateAccessToken(user);

        LoginUser parsed = jwtUtil.parseAccessToken(token);
        assertEquals(100L, parsed.getId());
        assertEquals("20260001", parsed.getUsername());
        assertEquals("张三", parsed.getName());
        assertEquals(Set.of("STUDENT"), parsed.getRoles());
        assertEquals(Set.of("exam:take", "score:view"), parsed.getPermissions());
        assertEquals(1, parsed.getRoleLevel());
        assertEquals(0, parsed.getSessionVersion());
        assertTrue(parsed.getJti() != null && !parsed.getJti().isBlank());
    }

    @Test
    void tamperedTokenRejected() {
        String token = jwtUtil.generateAccessToken(sampleUser());
        // 篡改 payload 中间段（把 20260001 换掉），签名校验必须失败
        String tampered = token.substring(0, token.length() - 10)
                + (token.endsWith("a") ? "b" : "a");
        assertThrows(JwtException.class, () -> jwtUtil.parseAccessToken(tampered));
    }

    @Test
    void expiredTokenRejected() throws InterruptedException {
        // 秒级短有效期：等 1.1s 后必然过期
        JwtUtil shortLived = new JwtUtil(SECRET, 1, 1);
        String token = shortLived.generateAccessToken(sampleUser());
        Thread.sleep(1100);
        assertThrows(ExpiredJwtException.class, () -> shortLived.parseAccessToken(token));
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessAndViceVersa() {
        String access = jwtUtil.generateAccessToken(sampleUser());
        String refresh = jwtUtil.generateRefreshToken(100L);

        // 类型隔离：Access 当作 Refresh 解析 / Refresh 当作 Access 解析都必须拒绝
        assertThrows(JwtException.class, () -> jwtUtil.parseRefreshToken(access));
        assertThrows(JwtException.class, () -> jwtUtil.parseAccessToken(refresh));
    }

    @Test
    void refreshTokenCarriesUserIdAndJti() {
        String refresh = jwtUtil.generateRefreshToken(100L, "fixed-jti-0001");
        JwtUtil.RefreshClaims claims = jwtUtil.parseRefreshToken(refresh);
        assertEquals(100L, claims.userId());
        assertEquals("fixed-jti-0001", claims.jti());
    }

    @Test
    void remainingTtlPositiveForFreshToken() {
        String access = jwtUtil.generateAccessToken(sampleUser());
        long remain = jwtUtil.getRemainingTtlSeconds(access);
        assertTrue(remain > 0 && remain <= 60, "剩余有效期应介于 0~60s，实际 " + remain);
    }

    @Test
    void weakSecretRejectedAtConstruction() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil("too-short", 60, 60));
    }
}
