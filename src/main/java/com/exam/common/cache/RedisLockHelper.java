package com.exam.common.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis SETNX 锁的共享助手：统一 tryLock / 按 token 安全解锁。
 *
 * <p>为何必须按 token 解锁：锁带 TTL（如交卷锁 exam.taking.submit.lock-ttl-seconds，默认 30s）。
 * 持锁线程若工作超过 TTL，锁可能已过期并被其他线程重新抢到；此时若无条件 {@code DEL key}，
 * 会删掉别人的锁——等待者提前退出、第三线程也能进锁区，"单飞"语义直接被破坏。
 * 因此解锁必须走 compare-and-delete：仅当当前值仍是自己的 token 时才删除。
 *
 * <p>诚实定性：交卷路径即便误删锁也不会造成"重复交卷"数据错误（下游还有 CAS + 唯一索引），
 * 但会削弱"三路竞态收敛为一个执行流"这层设计语义，并在 TTL 过期后放大 DB/MQ 压力。
 */
@Component
public class RedisLockHelper {

    /**
     * 原子解锁脚本：校验锁 token 一致才删除——防止自己的锁已过期、被其他线程持有时误删他人锁。
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisLockHelper(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** SETNX 加锁：成功返回 true；key 已存在返回 false。 */
    public boolean tryLock(String key, String token, Duration ttl) {
        Boolean locked = redis.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(locked);
    }

    /** 按 token 安全解锁：仅持有者本人可删；token 不匹配时返回 0（不删）。 */
    public Long unlock(String key, String token) {
        return redis.execute(UNLOCK_SCRIPT, List.of(key), token);
    }
}
