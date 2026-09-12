package com.exam.anticheat.collector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行为事件策略注册中心（spec「新增事件不动核心」场景的装配落地点，
 * 与判分的 {@code GradingStrategyRegistry} 同构）：
 *
 * <p>Spring 注入全部 {@link BehaviorEventCollector} 实现并按事件类型建索引——
 * 采集核心只依赖本注册中心的 {@code dispatch}，不知道具体策略存在。
 * 新增事件策略只需一个 @Component 实现类，注册中心与采集核心零改动；
 * 这就是"事件类型扩展不侵入核心"与判分侧"新增题型不改判分核心"的同一套答案。
 *
 * <p>与判分注册中心唯一的差异：分派不到不抛异常，而是回落 {@link UnknownEventCollector}
 * 兜底——行为采集是旁路，前端先于后端上线新事件时照常落库（见兜底策略类注释）。
 */
@Slf4j
@Component
public class BehaviorEventRegistry {

    private final Map<String, BehaviorEventCollector> strategies = new HashMap<>();
    private final UnknownEventCollector fallback;

    public BehaviorEventRegistry(List<BehaviorEventCollector> candidates, UnknownEventCollector fallback) {
        this.fallback = fallback;
        for (BehaviorEventCollector strategy : candidates) {
            if (strategy == fallback) {
                continue; // 兜底策略不参与类型索引，只作缺省分派
            }
            BehaviorEventCollector previous = strategies.put(strategy.eventType(), strategy);
            if (previous != null) {
                // 同事件类型重复注册是装配事故，直接快速失败避免随机分派
                throw new IllegalStateException("事件类型 " + strategy.eventType() + " 重复注册采集策略");
            }
            log.info("注册行为事件策略: {} -> {}", strategy.eventType(), strategy.getClass().getSimpleName());
        }
    }

    /** 按事件类型分派策略；未注册类型回落兜底策略（前向兼容，见 UnknownEventCollector）。 */
    public BehaviorEventCollector dispatch(String eventType) {
        BehaviorEventCollector strategy = strategies.get(eventType);
        if (strategy == null) {
            log.debug("未注册的行为事件类型，走兜底策略: {}", eventType);
            return fallback;
        }
        return strategy;
    }
}
