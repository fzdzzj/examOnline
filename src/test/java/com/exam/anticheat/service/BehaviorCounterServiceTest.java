package com.exam.anticheat.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 行为计数 TTL 原子性护栏（change fix-behavior-counter-ttl-atomicity，
 * spec-delta「行为计数键 TTL 的原子性」）：
 *
 * <p>{@link BehaviorCounterService#increment} 原为两步「INCR + 判 count==1 再 EXPIRE」非原子，
 * INCR 与 EXPIRE 间隙进程崩溃/Redis 重启会让键永不过期（键空间无界累积、超窗口计数残留影响
 * 阈值升级口径）。本套测试把改造后的形状钉死：
 * <ol>
 *   <li><b>首增置 TTL</b>：首次 INCR（count=1）后计数键即带 TTL（连测试 Redis db 15 跑真 Lua）；</li>
 *   <li><b>续增不重置 TTL</b>：后续 INCR 不得把已缩短的 TTL 重置回满额（原满额 12h）——
 *       若退出两步形态或脚本无条件 PEXPIRE，本用例必红；</li>
 *   <li><b>崩溃间隙红线</b>：INCR 与首置 TTL 必须在<b>同一次 Lua 单脚本 EVAL</b> 内原子完成，
 *       不得再出现「独立 INCR + 独立 EXPIRE」两步间隙形——任何独立 opsForValue().increment()
 *       或 redisTemplate.expire() 都会使本用例变红（两步形态即此间隙）；</li>
 *   <li><b>异常降级保留</b>：Redis 抛异常时按首次事件返回 1、不抛出（采集主链路零影响）。</li>
 * </ol>
 * TTL 语义用连等待真 Lua 锁定，崩溃间隙/降级用 mock 锁定调用形状。
 * 各用例用独立 key 并 @AfterEach 清理，避免跨用例相互污染。
 */
@SpringBootTest
@ActiveProfiles("test")
class BehaviorCounterServiceTest {

    /** 计数 key 前缀（与实现常量 KEY_PREFIX 对齐，防漂移）。 */
    private static final String KEY_PREFIX = "exam:behavior:count:";
    private static final String TYPE = "SWITCH_SCREEN";

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private BehaviorCounterService service;

    private final List<String> keys = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private String keyOf(long exam, long student) {
        String key = KEY_PREFIX + exam + ":" + student + ":" + TYPE;
        keys.add(key);
        return key;
    }

    // ==================== 真 Lua 行为（连测试 Redis db 15） ====================

    @Test
    @DisplayName("首增置 TTL：首次 INCR 后计数键即带过期时间（非无 TTL 残留）")
    void firstIncrementSetsTtl() {
        long exam = 1_100L;
        long student = 2_100L;
        String key = keyOf(exam, student);

        long count = service.increment(exam, student, TYPE);

        assertEquals(1L, count, "首次返回 1（语义锁定）");
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "首次写入后键必须已有 TTL（不得是无 TTL 的永驻键），实测 ttl=" + ttl);
    }

    @Test
    @DisplayName("续增不重置 TTL：后续 INCR 不把已缩短的 TTL 重置回满额 12h")
    void subsequentIncrementDoesNotResetTtl() {
        long exam = 1_101L;
        long student = 2_101L;
        String key = keyOf(exam, student);

        assertEquals(1L, service.increment(exam, student, TYPE));
        // 把 TTL 手动缩到 30s——若续增无条件/首增判断被破坏地重置 TTL，会弹回满额 12h
        redis.expire(key, 30, TimeUnit.SECONDS);

        assertEquals(2L, service.increment(exam, student, TYPE), "续增返回 2");

        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 300,
                "续增不得重置 TTL 到满额：缩到 30s 后仍应留在秒级（≤5 分钟），实测 ttl=" + ttl);
    }

    // ==================== 崩溃间隙红线（mock 调用形状） ====================

    @Test
    @DisplayName("崩溃间隙红线：INCR 与首置 TTL 同一 Lua 单脚本原子完成，无独立 INCR/EXPIRE 两步间隙")
    void atomicSingleScriptCallNoTwoStepGap() {
        long exam = 1_102L;
        long student = 2_102L;
        final String[] executedScript = new String[1];

        StringRedisTemplate mockRedis = mock(StringRedisTemplate.class);
        when(mockRedis.execute(any(), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    DefaultRedisScript<?> script = invocation.getArgument(0);
                    executedScript[0] = script.getScriptAsString();
                    return 1L;
                });
        BehaviorCounterService svc = new BehaviorCounterService(mockRedis);
        ReflectionTestUtils.setField(svc, "ttlHours", 12);

        long count = svc.increment(exam, student, TYPE);

        assertEquals(1L, count);
        // 恰一次单脚本 EVAL；不得再走独立 INCR / 独立 EXPIRE 两步
        verify(mockRedis, never()).opsForValue();
        verify(mockRedis, never()).expire(anyString(), any(Duration.class));
        // 脚本文本锁定「首次自增才置 TTL」的原子守卫
        String lua = executedScript[0];
        assertNotNull(lua, "必须走 Lua 单脚本原子化");
        assertTrue(lua.contains("incr"), "脚本须含 INCR: " + lua);
        assertTrue(lua.contains("expire"), "脚本须含首置 EXPIRE: " + lua);
        assertTrue(lua.contains("c == 1"), "脚本须在首次自增（c==1）时置 TTL: " + lua);
    }

    @Test
    @DisplayName("异常降级保留：Redis 抛异常按首次事件返回 1、不抛出，采集主链路零影响")
    void redisFailureDegradesToOneWithoutThrowing() {
        long exam = 1_103L;
        long student = 2_103L;

        StringRedisTemplate mockRedis = mock(StringRedisTemplate.class);
        when(mockRedis.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis down"));
        BehaviorCounterService svc = new BehaviorCounterService(mockRedis);
        ReflectionTestUtils.setField(svc, "ttlHours", 12);

        long count = svc.increment(exam, student, TYPE);

        assertEquals(1L, count, "Redis 异常须按首次事件返回 1 且不抛出");
    }
}