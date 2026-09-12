package com.exam.anticheat.collector;

import com.exam.anticheat.model.SeverityLevel;
import org.springframework.stereotype.Component;

/**
 * 失焦事件策略：考试页面失去输入焦点（window blur）。
 *
 * <p>复用阶段 5 已埋的失焦采集点。失焦含大量误触场景（输入法切换、系统通知弹窗），
 * 信号弱于切屏，基础严重度按"低"记录，不做次数升级——决策记录 §3.1 的阈值升级
 * 只针对切屏（SWITCH_SCREEN）。与切屏相同：只警告 + 记录，绝不强制交卷。
 */
@Component
public class WindowBlurEventCollector implements BehaviorEventCollector {

    @Override
    public String eventType() {
        return BehaviorEventTypes.WINDOW_BLUR;
    }

    @Override
    public SeverityLevel baseSeverity() {
        return SeverityLevel.LOW;
    }
}
