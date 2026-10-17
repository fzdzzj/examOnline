package com.exam.auth.service;

import com.exam.common.BusinessException;
import com.exam.common.RequestIdFilter;
import com.exam.common.ResponseCode;
import com.exam.config.AuthProperties;
import com.exam.monitoring.metrics.BusinessMetrics;
import com.exam.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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
 *
 * <p><b>为什么 Redis 调用异常要降级放行（harden-login-guard-redis-degradation）：</b>
 * 凭据校验（DB BCrypt）才是登录正确性的底座，本类的限流/锁定/失败计数只是暴力破解的<b>减速器</b>。
 * Redis 故障时若异常裸穿，登录路径五处 Redis 触点（登录限流/锁定检查/失败计数/锁定写入/计数清理，
 * 外加同脚本的验证码限流）全部 500——登录全停摆，连与 Redis 无关的有效凭据登录也被挡在门外
 * （clearFailures 在凭据校验<b>通过后</b>执行，不包则有效登录仍 500）。降级放行则退化为
 * 「减速器暂缺」：限流侧本就 fail-open（对齐 {@link com.exam.common.ratelimit.RateLimitInterceptor}，
 * Redis 故障期间该防护无论如何不生效），锁定与失败计数保护在 Redis 恢复后自愈（计数键按既有
 * TTL 自然过期），清理跳过无正确性影响。降级对齐鉴权拦截器先例
 * （{@link com.exam.auth.security.AuthenticationInterceptor}）：ERROR 日志 + 降级计数指标
 * （<b>复用</b> exam.auth.degraded，tag 按触点区分、代码常量有界，告警联动 AuthDegraded），
 * 不新增第二套降级哲学。try 范围收紧到只包 Redis 调用本身：正常拒绝/锁定的 throw 与阈值判定
 * 在 try 外，绝不能被降级分支吞掉。凭据校验不降级——校验失败仍按既有口径拒绝。
 */
@Slf4j
@Service
public class LoginGuardService {

    private static final String PREFIX_FAIL = "auth:fail:";
    private static final String PREFIX_LOCK = "auth:lock:";
    private static final String PREFIX_RATE = "auth:rl:";
    private static final String PREFIX_CODE_RL = "auth:coderl:";
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    /** 降级计数 tag：登录限流计数点（BusinessMetrics.countAuthDegraded，代码常量有界，无客户端可控输入） */
    static final String DEGRADED_LOGIN_RATE_LIMIT = "login_rate_limit";
    /** 降级计数 tag：登录锁定检查 */
    static final String DEGRADED_LOGIN_LOCK_CHECK = "login_lock_check";
    /** 降级计数 tag：失败计数递增 */
    static final String DEGRADED_LOGIN_FAIL_RECORD = "login_fail_record";
    /** 降级计数 tag：达到阈值后的锁定写入 */
    static final String DEGRADED_LOGIN_LOCK_WRITE = "login_lock_write";
    /** 降级计数 tag：登录成功后的失败计数清理 */
    static final String DEGRADED_LOGIN_FAIL_CLEAR = "login_fail_clear";
    /** 降级计数 tag：找回密码验证码限流计数点 */
    static final String DEGRADED_CODE_RATE_LIMIT = "code_rate_limit";

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
    private final BusinessMetrics businessMetrics;
    private final DefaultRedisScript<Long> incrWithTtlScript;

    public LoginGuardService(StringRedisTemplate redis, AuthProperties props, AuditLogService auditLogService,
                             BusinessMetrics businessMetrics) {
        this.redis = redis;
        this.props = props;
        this.auditLogService = auditLogService;
        this.businessMetrics = businessMetrics;
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setResultType(Long.class);
        s.setScriptText(INCR_WITH_TTL_LUA);
        this.incrWithTtlScript = s;
    }

    /** 限流检查：超出阈值抛 429（固定窗口计数，窗口内所有尝试均计数）。 */
    public void checkRateLimit(String username, String ip) {
        long count;
        try {
            count = incrementWithTtl(PREFIX_RATE + username + ":" + ip, RATE_WINDOW);
        } catch (Exception e) {
            // Redis 基础设施故障：降级放行（限流本就 fail-open，Redis 故障期间该防护无论如何不生效），
            // 先记日志/埋点再放行，保证降级对告警可观测
            log.error("登录守卫降级：Redis 登录限流计数失败，username={}，异常={}，requestId={}",
                    username, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countAuthDegraded(DEGRADED_LOGIN_RATE_LIMIT);
            return;
        }
        if (count > props.getRateLimit().getPerMinute()) {
            log.warn("登录限流触发, username={}, ip={}, count={}", username, ip, count);
            throw new BusinessException(ResponseCode.TOO_MANY_REQUESTS);
        }
    }

    /** 找回密码验证码限流：按 邮箱+IP 维度固定窗口计数（复用登录限流阈值量级），超限抛 429。 */
    public void checkCodeRateLimit(String email, String ip) {
        long count;
        try {
            count = incrementWithTtl(PREFIX_CODE_RL + email + ":" + ip, RATE_WINDOW);
        } catch (Exception e) {
            // 降级口径同登录限流：同一脚本同一降级族，Redis 故障期间 fail-open
            log.error("登录守卫降级：Redis 验证码限流计数失败，email={}，异常={}，requestId={}",
                    email, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countAuthDegraded(DEGRADED_CODE_RATE_LIMIT);
            return;
        }
        if (count > props.getRateLimit().getPerMinute()) {
            log.warn("找回密码验证码限流触发, email={}, ip={}, count={}", email, ip, count);
            throw new BusinessException(ResponseCode.TOO_MANY_REQUESTS);
        }
    }

    /** 锁定检查：账号在锁定期间直接拒绝。 */
    public void checkLocked(String username) {
        Long ttl;
        try {
            ttl = redis.getExpire(PREFIX_LOCK + username, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 降级放行：锁定检查暂缺（锁定/失败计数保护 Redis 恢复后自愈，凭据校验照常）
            log.error("登录守卫降级：Redis 锁定检查失败，username={}，异常={}，requestId={}",
                    username, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countAuthDegraded(DEGRADED_LOGIN_LOCK_CHECK);
            return;
        }
        if (ttl != null && ttl > 0) {
            long minutes = ttl / 60 + 1;
            throw new BusinessException(ResponseCode.ACCOUNT_LOCKED,
                    "账号已锁定，请约 " + minutes + " 分钟后重试");
        }
    }

    /** 记录一次失败：达到阈值则锁定并清零计数；锁定审计记录真实客户端 IP。 */
    public void recordFailure(String username, String ip) {
        String failKey = PREFIX_FAIL + username;
        long count;
        try {
            count = incrementWithTtl(failKey, Duration.ofMinutes(props.getLock().getLockMinutes()));
        } catch (Exception e) {
            // 降级：本次失败不计入（锁定保护暂缺），继续既有失败流程（凭据错误仍按既有口径拒绝）
            log.error("登录守卫降级：Redis 失败计数失败，username={}，异常={}，requestId={}",
                    username, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countAuthDegraded(DEGRADED_LOGIN_FAIL_RECORD);
            return;
        }
        int max = props.getLock().getMaxFailures();
        if (count >= max) {
            try {
                redis.opsForValue().set(PREFIX_LOCK + username, "1",
                        Duration.ofMinutes(props.getLock().getLockMinutes()));
                redis.delete(failKey);
            } catch (Exception e) {
                // 降级：放弃本次锁定（计数键未清理，下次失败重试自愈）；审计只在锁定真正成立时记
                log.error("登录守卫降级：Redis 锁定写入失败，username={}，异常={}，requestId={}",
                        username, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
                businessMetrics.countAuthDegraded(DEGRADED_LOGIN_LOCK_WRITE);
                return;
            }
            log.warn("账号 {} 连续失败 {} 次，锁定 {} 分钟", username, max, props.getLock().getLockMinutes());
            // 记录审计日志：真实客户端 IP（替代硬编码占位值）
            auditLogService.logAccountLock(username, ip, "连续登录失败 " + max + " 次");
        }
    }

    /** 登录成功后清除失败计数。 */
    public void clearFailures(String username) {
        try {
            redis.delete(PREFIX_FAIL + username);
        } catch (Exception e) {
            // 降级：跳过清理——有效凭据登录不得被挡在成功后的最后一步，计数键按既有 TTL 自然过期自愈
            log.error("登录守卫降级：Redis 失败计数清理失败，username={}，异常={}，requestId={}",
                    username, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countAuthDegraded(DEGRADED_LOGIN_FAIL_CLEAR);
        }
    }

    /** INCR + 首置 EXPIRE 原子执行，返回自增后的计数（键不存在视为 0 起点）。 */
    private long incrementWithTtl(String key, Duration ttl) {
        Long count = redis.execute(incrWithTtlScript, List.of(key), String.valueOf(ttl.toSeconds()));
        return count == null ? 0L : count;
    }
}
