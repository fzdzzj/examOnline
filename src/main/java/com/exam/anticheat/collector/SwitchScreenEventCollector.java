package com.exam.anticheat.collector;

import com.exam.anticheat.model.SeverityLevel;
import org.springframework.stereotype.Component;

/**
 * 切屏事件策略（spec「采集切屏事件」「切屏警告不交卷」场景）：
 *
 * <p>复用阶段 5 已埋的前端切屏采集点（visibilitychange 上报），本类只是把该事件
 * 纳入统一策略体系。基础严重度为"低"——单次切屏多为误触（系统弹窗、误切标签页），
 * 决策记录 §3.1 明确：只警告 + 记录，绝不强制交卷，是否处置由教师事后依行为日志判定。
 *
 * <p>严重度升级（切屏次数达到阈值提升严重度）在本策略内叠加判定，
 * 采集核心对此毫不知情——升级规则调整只改本类。
 */
@Component
public class SwitchScreenEventCollector implements BehaviorEventCollector {

    @Override
    public String eventType() {
        return BehaviorEventTypes.SWITCH_SCREEN;
    }

    @Override
    public SeverityLevel baseSeverity() {
        // 单次切屏按"低"记录；次数阈值升级在 judge 中叠加（严重度分级任务落地）
        return SeverityLevel.LOW;
    }
}
