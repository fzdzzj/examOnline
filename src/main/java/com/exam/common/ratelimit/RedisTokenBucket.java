package com.exam.common.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 令牌桶（add-slow-sql-and-rate-limit task3）：用 Lua 脚本在 Redis 单线程内一次 EVAL 完成
 * 「读桶值 → 按经过时间补令牌 → 扣 1 → 写回 → 设置 key 过期」，返回是否放行（1/0）。
 *
 * <p><b>为什么令牌桶必须用 Lua 保证原子：</b>一个令牌桶请求本质是「读当前桶值 + 判断是否够扣 +
 * 扣减」三个动作。若这三步各自独立执行（先 GET 再 DECR），并发下多个线程可能读到同一个剩余值，
 * 各自判断"够扣"后都放行，导致<b>超发</b>（瞬时放行超过桶容量）。Redis 是单线程模型，一个 EVAL
 * 脚本会一次性串行执行完、期间不会穿插其他命令，因此把「读-判-扣」写进一个 Lua 即天然无竞态——
 * 这就是为什么不能拆成几条 Redis 命令在 Java 里拼，而必须整体放进脚本。
 *
 * <p><b>为什么按接口维度（而非用户维度）限流：</b>这里的限流目标是<b>保护整条后端链路</b>，
 * 应对 5000 人开考时的拉卷/交卷瞬时尖峰——尖峰落在接口本身，与具体是哪个用户无关。按接口维度
 * 限流 = 后端以配置速率平滑放行、DB/MQ 不被瞬时流量打垮（spec「峰值可抗」场景）；若按用户维度，
 * 每用户有独立桶，5000 并发仍会整体压垮后端，且违背"保护链路而非惩罚单用户"的意图。
 *
 * <p><b>按时间补令牌：</b>桶每被访问一次，先从 Redis TIME 取当前秒+微秒，按 {@code elapsed * qps}
 * 补足令牌（封顶 capacity，不无限累积），再决定是否扣 1 放行。用 Redis TIME 而非客户端时钟，
 * 避免多实例时钟漂移导致补令牌不一致。key 每次访问续 TTL，长期无请求后自动过期、下次重建满桶，
 * 避免递增 eval 计数/旧桶残留占用内存。
 */
@Slf4j
@Component
public class RedisTokenBucket {

    /** 桶 key 前缀：exam:ratelimit:{接口标识}（接口标识 = 注解 key 或 Controller.方法名，见 RateLimitInterceptor） */
    public static final String KEY_PREFIX = "exam:ratelimit:";

    /**
     * Lua 令牌桶脚本（参数：KEYS[1]=桶key，ARGV[1]=now 秒、ARGV[2]=capacity、ARGV[3]=qps、ARGV[4]=key TTL 秒）。
     * 返回 1 放行 / 0 拒绝。整个流程单次 EVAL 原子完成。
     *
     * <p>「现在」由客户端透传而不是脚本内调 Redis {@code TIME}：Redis 视 TIME/CONFIG 为<b>非确定性</b>
     * 命令，脚本内先读它们再执行写会触发脚本复制机制拒绝（"Write commands not allowed after
     * non deterministic commands"）。把时间作 ARGV 传入，脚本对相同入参结果确定，即可安全复制到从库；
     * 时间从 JVM 现取（多实例时钟漂移在短时间窗口内可忽略），补令牌逻辑不变。
     */
    private static final String LUA =
            "local now = tonumber(ARGV[1])\n" +
            "local capacity = tonumber(ARGV[2])\n" +
            "local qps = tonumber(ARGV[3])\n" +
            "local ttl = ARGV[4]\n" +
            // 读上一个时间戳；无记录说明桶首次使用，视为"满桶且从现在开始"
            "local last = redis.call('hget', KEYS[1], 'ts')\n" +
            "if last == false then last = now end\n" +
            // 读当前剩余令牌；无记录视为满桶（capacity）
            "local left = redis.call('hget', KEYS[1], 'left')\n" +
            "if left == false then left = capacity else left = tonumber(left) end\n" +
            // 按经过时间补令牌：新增 = elapsed * qps，封顶 capacity（不欠账、不无限累积）
            "local elapsed = now - tonumber(last)\n" +
            "if elapsed > 0 then\n" +
            "  left = math.min(capacity, left + elapsed * qps)\n" +
            "end\n" +
            "local allowed = 0\n" +
            "if left >= 1 then\n" +
            "  left = left - 1\n" +
            "  allowed = 1\n" +
            "end\n" +
            // 写回剩余令牌与时间戳，并续期 key TTL（自动回收闲置桶）
            "redis.call('hset', KEYS[1], 'left', left)\n" +
            "redis.call('hset', KEYS[1], 'ts', now)\n" +
            "redis.call('expire', KEYS[1], ttl)\n" +
            "return allowed\n";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> script;
    private final long keyTtlSeconds;

    public RedisTokenBucket(StringRedisTemplate redisTemplate,
                            @Value("${exam.ratelimit.key-ttl-seconds:120}") long keyTtlSeconds) {
        this.redisTemplate = redisTemplate;
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setResultType(Long.class);
        s.setScriptText(LUA);
        this.script = s;
        this.keyTtlSeconds = keyTtlSeconds;
    }

    /** 取指定接口维度的桶 key（供拦截器与测试复用同一派生规则，保证 E2E 可算准同一把桶）。 */
    public static String key(String interfaceId) {
        return KEY_PREFIX + interfaceId;
    }

    /**
     * 原子判定一次请求是否放行：读桶 + 按时间补令牌 + 扣 1 + 写回 + 续期，单次 EVAL 完成。
     *
     * @param key       接口维度桶 key（见 {@link #key(String)}）
     * @param capacity  桶容量（瞬时突发上限）
     * @param qps       每秒补充速率（稳态放行均值）
     * @return true 放行；false 桶空拒绝
     */
    public boolean tryAcquire(String key, int capacity, double qps) {
        // now 取 JVM 当前秒（含小数），作为 ARGV[1] 传入脚本（见 LUA：不用 Redis TIME 以保证脚本可复制）
        String now = Double.toString(System.currentTimeMillis() / 1000.0);
        Long result = redisTemplate.execute(script, List.of(key), now,
                String.valueOf((int) capacity), String.valueOf(qps), String.valueOf(keyTtlSeconds));
        return result != null && result == 1L;
    }
}