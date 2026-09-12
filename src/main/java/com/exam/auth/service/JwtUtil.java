package com.exam.auth.service;

import com.exam.auth.security.LoginUser;
import com.exam.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JWT 工具（jjwt 0.11.5）：
 * - Access Token：30 分钟，携带 jti / 角色 / 权限点 / 会话版本，业务鉴权用；
 * - Refresh Token：7 天，仅携带 userId + jti，用于换取新双 Token（轮换 + 复用检测）。
 *
 * <p>声明设计：
 * <pre>
 * Access  : sub=userId, username, name, email, roles(逗号拼接), permissions(逗号拼接),
 *           roleLevel, sv(会话版本), jti, type=access, iat, exp
 * Refresh : sub=userId, jti, type=refresh, iat, exp
 * </pre>
 */
@Component
public class JwtUtil {

    public static final String CLAIM_TYPE = "type";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_PERMISSIONS = "permissions";
    public static final String CLAIM_ROLE_LEVEL = "roleLevel";
    public static final String CLAIM_SESSION_VERSION = "sv";

    private final Key key;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public JwtUtil(AuthProperties props) {
        this(props.getJwt().getSecret(),
                Duration.ofMinutes(props.getJwt().getAccessExpireMinutes()).toSeconds(),
                Duration.ofDays(props.getJwt().getRefreshExpireDays()).toSeconds());
    }

    /** 直接指定密钥与有效期（测试用：可传秒级短有效期验证过期逻辑）。 */
    public JwtUtil(String secret, long accessTtlSeconds, long refreshTtlSeconds) {
        // HS256 要求密钥 >= 32 字节；不足时抛异常提示配置问题（防弱密钥上线）
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT secret 长度必须 >= 32 字节（当前 " + secretBytes.length + "），请检查 exam.auth.jwt.secret 配置");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    /** 签发 Access Token（含 jti/角色/权限/会话版本）。 */
    public String generateAccessToken(LoginUser user) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(String.valueOf(user.getId()))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim("username", user.getUsername())
                .claim("name", user.getName())
                .claim("email", user.getEmail())
                .claim(CLAIM_ROLES, String.join(",", user.getRoles()))
                .claim(CLAIM_PERMISSIONS, String.join(",", user.getPermissions()))
                .claim(CLAIM_ROLE_LEVEL, user.getRoleLevel())
                .claim(CLAIM_SESSION_VERSION, user.getSessionVersion())
                .setId(newJti())
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + accessTtlSeconds * 1000))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** 签发 Refresh Token（仅 userId + jti，内部生成 jti）。 */
    public String generateRefreshToken(Long userId) {
        return generateRefreshToken(userId, newJti());
    }

    /** 签发 Refresh Token（调用方传入 jti，便于同时登记到 Redis 会话）。 */
    public String generateRefreshToken(Long userId, String jti) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .setId(jti)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + refreshTtlSeconds * 1000))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** 解析 Access Token；签名错误/过期/类型不符抛 JwtException，由拦截器转 401。 */
    public LoginUser parseAccessToken(String token) {
        Claims claims = parse(token);
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new io.jsonwebtoken.JwtException("token type must be access");
        }
        LoginUser user = new LoginUser();
        user.setId(Long.valueOf(claims.getSubject()));
        user.setUsername(claims.get("username", String.class));
        user.setName(claims.get("name", String.class));
        user.setEmail(claims.get("email", String.class));
        user.setRoles(split(claims.get(CLAIM_ROLES, String.class)));
        user.setPermissions(split(claims.get(CLAIM_PERMISSIONS, String.class)));
        // 数值声明经 JSON 反序列化可能为 Integer，统一按 Number 转换防 ClassCastException
        Number roleLevel = claims.get(CLAIM_ROLE_LEVEL, Number.class);
        user.setRoleLevel(roleLevel == null ? 0 : roleLevel.intValue());
        Number sv = claims.get(CLAIM_SESSION_VERSION, Number.class);
        user.setSessionVersion(sv == null ? 0 : sv.longValue());
        user.setJti(claims.getId());
        return user;
    }

    /** 解析 Refresh Token；返回用户 ID 与 jti（复用检测用）。 */
    public RefreshClaims parseRefreshToken(String token) {
        Claims claims = parse(token);
        if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new io.jsonwebtoken.JwtException("token type must be refresh");
        }
        return new RefreshClaims(Long.valueOf(claims.getSubject()), claims.getId());
    }

    /** 取令牌剩余有效秒数（黑名单 TTL 用；过期返回 0）。 */
    public long getRemainingTtlSeconds(String token) {
        Date exp = parse(token).getExpiration();
        long remain = (exp.getTime() - System.currentTimeMillis()) / 1000;
        return Math.max(0, remain);
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }

    public long getRefreshTtlSeconds() {
        return refreshTtlSeconds;
    }

    public String newJti() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Set<String> split(String joined) {
        if (joined == null || joined.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(joined.split(","))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Refresh Token 解析结果。 */
    public record RefreshClaims(Long userId, String jti) {
    }
}
