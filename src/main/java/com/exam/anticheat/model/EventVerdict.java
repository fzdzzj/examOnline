package com.exam.anticheat.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 事件判定结果：策略（{@code BehaviorEventCollector}）对一次行为事件的裁决。
 *
 * <p>采集核心拿到判定后只做三件事——合并明细、落库、把判定回传给调用方
 * （如切屏警告提示）。核心不理解任何具体事件语义，这正是"新增事件不动核心"的关键：
 * 严重度怎么定、要不要警告、明细补什么字段，全部封装在各策略实现内部。
 *
 * @param severity  最终严重度（基础级 + 策略升级逻辑的共同结果）
 * @param warn      是否需要前端警告弹窗（切屏 true——只警告不强制交卷）
 * @param extraData 策略补充的事件明细（如切屏次数/升级标记），与客户端明细合并后落库，可为 null
 */
public record EventVerdict(SeverityLevel severity, boolean warn, JsonNode extraData) {

    /** 仅严重度、无警告无补充明细的最简判定（多数事件的基础形态）。 */
    public static EventVerdict of(SeverityLevel severity) {
        return new EventVerdict(severity, false, null);
    }

    /** 带警告与补充明细的判定（切屏计数升级等场景）。 */
    public static EventVerdict of(SeverityLevel severity, boolean warn, JsonNode extraData) {
        return new EventVerdict(severity, warn, extraData);
    }
}
