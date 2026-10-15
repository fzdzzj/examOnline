package com.exam.anticheat.collector;

import com.exam.anticheat.model.BehaviorEventContext;
import com.exam.anticheat.model.EventVerdict;
import com.exam.anticheat.model.SeverityLevel;
import org.springframework.stereotype.Component;

/**
 * 退出全屏事件策略（spec add-anti-cheat-restrictions「采集全屏退出事件」场景）：
 * 考试开启 {@code fullScreen} 时，作答端监听 {@code fullscreenchange}，退出全屏即旁路上报本事件。
 *
 * <p>基础严重度"中"：退出全屏是作答端可见的安全模式被解除，教师应能在行为时间线上看到；
 * 与切屏的差别在于切屏已有阈值升级（可能只是误触），而退出全屏是<b>显式的模式解除动作</b>，
 * 因此直接从"中"起步。仍然只警告 + 记录，<b>绝不强制交卷</b>（决策记录 §3.1）。
 */
@Component
public class FullScreenExitEventCollector implements BehaviorEventCollector {

    @Override
    public String eventType() {
        return BehaviorEventTypes.FULLSCREEN_EXIT;
    }

    @Override
    public SeverityLevel baseSeverity() {
        return SeverityLevel.MEDIUM;
    }

    @Override
    public boolean shouldWarn() {
        // 提示学生"本场要求全屏作答"，仅提示不处置
        return true;
    }

    @Override
    public EventVerdict judge(BehaviorEventContext context) {
        // 无次数升级：基础"中"级 + 警告标记（接口默认 judge 不带警告，须显式构造）
        return EventVerdict.of(baseSeverity(), shouldWarn(), null);
    }
}
