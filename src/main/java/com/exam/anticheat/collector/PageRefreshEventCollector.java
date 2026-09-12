package com.exam.anticheat.collector;

import com.exam.anticheat.model.SeverityLevel;
import org.springframework.stereotype.Component;

/**
 * 刷新事件策略：答题页刷新/重载（本阶段补齐的事件类型之一）。
 *
 * <p>背景（决策记录 §3.4）：刷新刷题已被"进入时锁定个人快照"根治——刷新/重进
 * 返回同一快照不重新抽题；但频繁刷新仍可能是试探行为，值得留下轨迹。
 * 基础严重度按"中"：比误触切屏更值得教师留意，又远不到系统异常级别。
 */
@Component
public class PageRefreshEventCollector implements BehaviorEventCollector {

    @Override
    public String eventType() {
        return BehaviorEventTypes.PAGE_REFRESH;
    }

    @Override
    public SeverityLevel baseSeverity() {
        return SeverityLevel.MEDIUM;
    }
}
