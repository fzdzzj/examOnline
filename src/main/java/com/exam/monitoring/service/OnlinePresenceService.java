package com.exam.monitoring.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 在线状态服务（spec add-anti-cheat「监考大屏/实时状态展示」的实时数据源）：
 *
 * <p>机制：学生每次答题活动（进入/拉题/30s 自动保存心跳）刷新一个带 TTL 的 Redis key
 * （{@code exam:monitor:online:{examId}:{studentId}}）。key 存在即在线，过期即离线——
 * 无需显式下线，断网/关页的学生在 TTL 内自然转为离线（决策记录 §2.2 断线场景天然兼容）。
 * 大屏轮询本服务聚合的快照即可，不给 DB 任何实时压力（proposal 风险应对：Redis 实时计数）。
 *
 * <p>旁路原则：写/读在线状态的 Redis 异常一律降级（touch 静默、查询按全员离线），
 * 监控辅助数据绝不影响答题与监考主链路。
 */
@Slf4j
@Service
public class OnlinePresenceService {

    /** 在线状态 key 前缀：exam:monitor:online:{examId}:{studentId} */
    static final String KEY_PREFIX = "exam:monitor:online:";

    private final StringRedisTemplate redisTemplate;

    /** 在线判定窗口（秒）：大于自动保存周期（30s）的合理心跳超时 */
    @Value("${exam.monitor.online-ttl-seconds:60}")
    private int onlineTtlSeconds;

    public OnlinePresenceService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 心跳：刷新学生在线状态（TTL 内无后续心跳则自动视为离线）。Redis 异常静默降级。 */
    public void touch(Long examId, Long studentId) {
        try {
            redisTemplate.opsForValue().set(key(examId, studentId), "1",
                    Duration.ofSeconds(onlineTtlSeconds));
        } catch (Exception e) {
            log.error("在线心跳写入失败（旁路忽略）: exam={} student={}", examId, studentId, e);
        }
    }

    /**
     * 批量判定在线学生集合：一次 MGET 查询全部学生的 key（大屏每轮轮询一次，
     * 不逐学生往返）。返回入参中仍然在线的学生 ID 子集。
     */
    public Set<Long> onlineOf(Long examId, List<Long> studentIds) {
        if (studentIds == null || studentIds.isEmpty()) {
            return Set.of();
        }
        try {
            List<String> keys = new ArrayList<>(studentIds.size());
            for (Long studentId : studentIds) {
                keys.add(key(examId, studentId));
            }
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            Set<Long> online = new HashSet<>();
            if (values != null) {
                for (int i = 0; i < values.size(); i++) {
                    if (values.get(i) != null) {
                        online.add(studentIds.get(i));
                    }
                }
            }
            return online;
        } catch (Exception e) {
            log.error("在线状态批量查询失败（按全员离线降级）: exam={}", examId, e);
            return Set.of();
        }
    }

    private String key(Long examId, Long studentId) {
        return KEY_PREFIX + examId + ":" + studentId;
    }
}
