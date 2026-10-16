package com.exam.auth.service;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.AuthProperties;
import com.exam.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 登录防护：
 * <ul>
 *   <li>登录锁定：连续失败 {@code maxFailures} 次锁 {@code lockMinutes} 分钟（Redis 计数），
 *       锁定期间拒绝登录；</li>
 *   <li>登录限流：按 账号+IP 维度固定窗口计数，超过 {@code perMinute} 次/分钟返回 429；</li>
 *   <li>找回密码验证码限流：按 邮箱+IP 维度固定窗口计数（复用同一阈值量级），超限返回 429。</li>
 * </ul>
 *
 * <p><b>计数原子化（harden-auth-edge-cases）</b>：三处计数（登录限流 / 失败计数 / 验证码限流）的
 * 「INCR + 首置 EXPIRE」经 Lua 脚本单次 EVAL 完成——若拆成两步，INCR 后进程崩溃/连接异常会让键
 * 永不过期（账号永久限流/永久锁定）。脚本形态与 {@code RedisTokenBucket} 的 DefaultRedisScript
 * 先例一致：KEYS[1]=计数键，ARGV[1]=TTL 秒，首次自增（计数=1）时顺带置过期，返回自增后的计数。
 */
@Slf4j
@Service
public class LoginGuardService {

    private static final String PREFIX_FAIL = "auth:fail:";
    private static final String PREFIX_LOCK = "auth:lock:";
    private static final String PREFIX_RATE = "auth:rl:";
    private static final String PREFIX_CODE_RL = "auth:coderl:";
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    /** INCR 并首次置 EXPIRE 的原子脚本：防止「已递增但永不过期」的计数键。 */
    private static final String INCR_WITH_TTL_LUA =
            "local c = redis.call('incr', KEYS[1])\n" +
            "if c == 1 then\n" +
            "  redis.call('expire', KEYS[1], ARGV[1])\n" +
            "end\n" +
            "return c\n";

    private final StringRedisTemplate redis;
    private final AuthProperties props;
    private final AuditLogService auditLogService;
    private final DefaultRedisScript<Long> incrWithTtlScript;

    public LoginGuardService(StringRedisTemplate redis, AuthProperties props, AuditLogService auditLogService) {
        this.redis = redis;
        this.props = props;
        this.auditLogService = auditLogService;
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setResultType(Long.class);
        s.setScriptText(INCR_WITH_TTL_LUA);
        this.incrWithTtlScript = s;
    }

    /** 限流检查：超出阈值抛 429（固定窗口计数，窗口内所有尝试均计数）。 */
    public void checkRateLimit(String username, String ip) {
        String key = PREFIX_RATE + username + ":" + ip;
        long count = incrementWithTtl(key, RATE_WINDOW);
        if (count > props.getRateLimit().getPerMinute()) {
            log.warn("登录限流触发, username={}, ip={}, count={}", username, ip, count);
            throw new BusinessException(ResponseCode.TOO_MANY_REQUESTS);
        }
    }

    /** 找回密码验证码限流：按 邮箱+IP 维度固定窗口计数（复用登录限流阈值量级），超限抛 429。 */
    public void checkCodeRateLimit(String email, String ip) {
        String key = PREFIX_CODE_RL + email + ":" + ip;
        long count = incrementWithTtl(key, RATE_WINDOW);
        if (count > props.getRateLimit().getPerMinute()) {
            log.warn("找回密码验证码限流触发, email={}, ip={}, count={}", email, ip, count);
            throw new BusinessException(ResponseCode.TOO_MANY_REQUESTS);
        }
    }

    /** 锁定检查：账号在锁定期间直接拒绝。 */
    public void checkLocked(String username) {
        Long ttl = redis.getExpire(PREFIX_LOCK + username, TimeUnit.SECONDS);
        if (ttl != null && ttl > 0) {
            long minutes = ttl / 60 + 1;
            throw new BusinessException(ResponseCode.ACCOUNT_LOCKED,
                    "账号已锁定，请约 " + minutes + " 分钟后重试");
        }
    }

    /** 记录一次失败：达到阈值则锁定并清零计数；锁定审计记录真实客户端 IP。 */
    public void recordFailure(String username, String ip) {
        String failKey = PREFIX_FAIL + username;
        long count = incrementWithTtl(failKey, Duration.ofMinutes(props.getLock().getLockMinutes()));
        int max = props.getLock().getMaxFailures();
        if (count >= max) {
            redis.opsForValue().set(PREFIX_LOCK + username, "1",
                    Duration.ofMinutes(props.getLock().getLockMinutes()));
            redis.delete(failKey);
            log.warn("账号 {} 连续失败 {} 次，锁定 {} 分钟", username, max, props.getLock().getLockMinutes());
            // 记录审计日志：真实客户端 IP（替代硬编码占位值）
            auditLogService.logAccountLock(username, ip, "连续登录失败 " + max + " 次");
        }
    }

    /** 登录成功后清除失败计数。 */
    public void clearFailures(String username) {
        redis.delete(PREFIX_FAIL + username);
    }

    /** INCR + 首置 EXPIRE 原子执行，返回自增后的计数（键不存在视为 0 起点）。 */
    private long incrementWithTtl(String key, Duration ttl) {
        Long count = redis.execute(incrWithTtlScript, List.of(key), String.valueOf(ttl.toSeconds()));
        return count == null ? 0L : count;
    }
}
