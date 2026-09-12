package com.exam.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Redis 令牌存储（主动失效的核心）：
 * <ul>
 *   <li>黑名单：{@code auth:bl:{jti}} —— 登出/踢人/改密码写入，TTL=该 Token 剩余有效期；</li>
 *   <li>会话版本：{@code auth:sv:{userId}} —— 改密码/踢人/Refresh 复用检测时 INCR，
 *       旧 Access Token 携带的版本低于当前值即失效（全端下线）；</li>
 *   <li>Refresh 会话：{@code auth:refresh:{userId}} —— 记录当前有效 Refresh 的 jti，
 *       刷新时原子轮换（Lua 比较并替换），发现旧 Refresh 复用即全端下线。</li>
 * </ul>
 */
@Slf4j
@Service
public class TokenStoreService {

    private static final String PREFIX_BLACKLIST = "auth:bl:";
    private static final String PREFIX_SESSION_VERSION = "auth:sv:";
    private static final String PREFIX_REFRESH = "auth:refresh:";
    private static final String PREFIX_RESET_CODE = "auth:reset:";

    /**
     * Refresh 原子轮换脚本：
     * - 无会话（key 不存在）→ -1；
     * - 当前 jti == 提交 jti → 正常轮换，SET 新 jti 并重置 TTL → 1；
     * - 当前 jti != 提交 jti → 复用 → 0（由调用方触发全端下线）。
     */
    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>(
            "local cur = redis.call('GET', KEYS[1]) " +
                    "if cur == false then return -1 end " +
                    "if cur == ARGV[1] then " +
                    "  redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]) " +
                    "  return 1 " +
                    "end " +
                    "return 0",
            Long.class);

    /** 轮换结果：OK=正常轮换 REUSED=旧 Refresh 被复用 NO_SESSION=无有效会话 */
    public enum RotateResult { OK, REUSED, NO_SESSION }

    private final StringRedisTemplate redis;

    public TokenStoreService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ---------- 黑名单 ----------

    /** 将 Token 的 jti 写入黑名单，保留其剩余有效期。 */
    public void blacklistAccessToken(String jti, long ttlSeconds) {
        if (jti == null || ttlSeconds <= 0) {
            return;
        }
        redis.opsForValue().set(PREFIX_BLACKLIST + jti, "1", Duration.ofSeconds(ttlSeconds));
        log.info("Access Token 已加入黑名单, jti={}, ttl={}s", jti, ttlSeconds);
    }

    public boolean isBlacklisted(String jti) {
        return jti != null && Boolean.TRUE.equals(redis.hasKey(PREFIX_BLACKLIST + jti));
    }

    // ---------- 会话版本（全端下线） ----------

    public long getSessionVersion(Long userId) {
        String v = redis.opsForValue().get(PREFIX_SESSION_VERSION + userId);
        return v == null ? 0L : Long.parseLong(v);
    }

    /** 递增会话版本：该用户所有旧 Access Token 立即失效。 */
    public long bumpSessionVersion(Long userId) {
        Long v = redis.opsForValue().increment(PREFIX_SESSION_VERSION + userId);
        log.info("用户 {} 会话版本已递增 -> {}", userId, v);
        return v == null ? 0L : v;
    }

    // ---------- Refresh 会话 ----------

    /** 登录/刷新成功后记录当前有效 Refresh jti（单会话模型：新登录会顶掉旧 Refresh）。 */
    public void storeRefresh(Long userId, String refreshJti, long ttlSeconds) {
        redis.opsForValue().set(PREFIX_REFRESH + userId, refreshJti, Duration.ofSeconds(ttlSeconds));
    }

    /**
     * 原子轮换 Refresh（Lua 比较并替换），杜绝并发刷新下的双写竞态。
     * 复用检测：旧 Refresh 已被轮换却再次提交 → REUSED。
     */
    public RotateResult rotateRefresh(Long userId, String oldJti, String newJti, long ttlSeconds) {
        Long r = redis.execute(ROTATE_SCRIPT, List.of(PREFIX_REFRESH + userId), oldJti, newJti, String.valueOf(ttlSeconds));
        if (r == null) {
            return RotateResult.NO_SESSION;
        }
        return switch (r.intValue()) {
            case 1 -> RotateResult.OK;
            case 0 -> RotateResult.REUSED;
            default -> RotateResult.NO_SESSION;
        };
    }

    /** 删除用户的 Refresh 会话（登出/全端下线时）。 */
    public void removeRefresh(Long userId) {
        redis.delete(PREFIX_REFRESH + userId);
    }

    public String getCurrentRefreshJti(Long userId) {
        return redis.opsForValue().get(PREFIX_REFRESH + userId);
    }

    // ---------- 找回密码验证码 ----------

    /** 保存单次重置验证码（TTL 内有效）。 */
    public void storeResetCode(String email, String code, Duration ttl) {
        redis.opsForValue().set(PREFIX_RESET_CODE + email, code, ttl);
    }

    /** 取出并删除验证码（GETDEL 保证单次使用；并发下仅一个请求能取到）。 */
    public String consumeResetCode(String email) {
        return redis.opsForValue().getAndDelete(PREFIX_RESET_CODE + email);
    }
}
