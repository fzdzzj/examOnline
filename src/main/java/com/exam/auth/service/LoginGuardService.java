package com.exam.auth.service;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 登录防护：
 * <ul>
 *   <li>登录锁定：连续失败 {@code maxFailures} 次锁 {@code lockMinutes} 分钟（Redis 计数），
 *       锁定期间拒绝登录；</li>
 *   <li>登录限流：按 账号+IP 维度固定窗口计数，超过 {@code perMinute} 次/分钟返回 429。</li>
 * </ul>
 */
@Slf4j
@Service
public class LoginGuardService {

    private static final String PREFIX_FAIL = "auth:fail:";
    private static final String PREFIX_LOCK = "auth:lock:";
    private static final String PREFIX_RATE = "auth:rl:";
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final AuthProperties props;

    public LoginGuardService(StringRedisTemplate redis, AuthProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** 限流检查：超出阈值抛 429（固定窗口计数，窗口内所有尝试均计数）。 */
    public void checkRateLimit(String username, String ip) {
        String key = PREFIX_RATE + username + ":" + ip;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, RATE_WINDOW);
        }
        if (count != null && count > props.getRateLimit().getPerMinute()) {
            log.warn("登录限流触发, username={}, ip={}, count={}", username, ip, count);
            throw new BusinessException(ResponseCode.TOO_MANY_REQUESTS);
        }
    }

    /** 锁定检查：账号在锁定期间直接拒绝。 */
    public void checkLocked(String username) {
        Long ttl = redis.getExpire(PREFIX_LOCK + username, java.util.concurrent.TimeUnit.SECONDS);
        if (ttl != null && ttl > 0) {
            long minutes = ttl / 60 + 1;
            throw new BusinessException(ResponseCode.ACCOUNT_LOCKED,
                    "账号已锁定，请约 " + minutes + " 分钟后重试");
        }
    }

    /** 记录一次失败：达到阈值则锁定并清零计数。 */
    public void recordFailure(String username) {
        String failKey = PREFIX_FAIL + username;
        Long count = redis.opsForValue().increment(failKey);
        if (count != null && count == 1L) {
            redis.expire(failKey, Duration.ofMinutes(props.getLock().getLockMinutes()));
        }
        int max = props.getLock().getMaxFailures();
        if (count != null && count >= max) {
            redis.opsForValue().set(PREFIX_LOCK + username, "1",
                    Duration.ofMinutes(props.getLock().getLockMinutes()));
            redis.delete(failKey);
            log.warn("账号 {} 连续失败 {} 次，锁定 {} 分钟", username, max, props.getLock().getLockMinutes());
        }
    }

    /** 登录成功后清除失败计数。 */
    public void clearFailures(String username) {
        redis.delete(PREFIX_FAIL + username);
    }
}
