package com.exam.anticheat.collector;

import com.exam.anticheat.model.BehaviorEventContext;
import com.exam.anticheat.model.EventVerdict;
import com.exam.anticheat.model.SeverityLevel;
import com.exam.anticheat.service.BehaviorCounterService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 切屏事件策略（spec「采集切屏事件」「切屏警告不交卷」「切屏超过阈值」场景）：
 *
 * <p>复用阶段 5 已埋的前端切屏采集点（visibilitychange 上报），本类只是把该事件
 * 纳入统一策略体系。<b>只警告 + 记录，绝不强制交卷</b>（决策记录 §3.1）：
 * 是否处置由教师事后依行为日志判定，本策略乃至整个采集体系没有任何强制交卷的代码路径。
 *
 * <p>严重度升级是"策略内聚事件规则"的样板——采集核心对此毫不知情：
 * <ol>
 *   <li>每次切屏经 {@link BehaviorCounterService} 累计次数（Redis 原子自增）；</li>
 *   <li>达到 medium-threshold 升"中"、达到 high-threshold 升"高"（阈值可配置）；</li>
 *   <li>升级时在事件明细写入升级标记（escalated=true + 当前次数），满足规范
 *      「事件严重度提升 AND 记录升级标记」。</li>
 * </ol>
 * 若未来要改"按时间窗计数/按班级差异化阈值"，只改本类，核心与其他事件零感知。
 */
@Component
public class SwitchScreenEventCollector implements BehaviorEventCollector {

    private final BehaviorCounterService counter;

    /** 升"中"阈值：切屏次数达到该值严重度升为 2（0=关闭该档升级） */
    @Value("${exam.anticheat.switch-screen.medium-threshold:3}")
    private int mediumThreshold;

    /** 升"高"阈值：切屏次数达到该值严重度升为 3（0=关闭该档升级） */
    @Value("${exam.anticheat.switch-screen.high-threshold:5}")
    private int highThreshold;

    public SwitchScreenEventCollector(BehaviorCounterService counter) {
        this.counter = counter;
    }

    @Override
    public String eventType() {
        return BehaviorEventTypes.SWITCH_SCREEN;
    }

    @Override
    public SeverityLevel baseSeverity() {
        // 单次切屏按"低"记录（误触常见）；动态升级见 judge
        return SeverityLevel.LOW;
    }

    @Override
    public boolean shouldWarn() {
        // 每次切屏都警告提示（前端弹提醒），但只警告——绝不触发交卷
        return true;
    }

    @Override
    public EventVerdict judge(BehaviorEventContext context) {
        long count = counter.increment(context.examId(), context.studentId(), eventType());
        SeverityLevel severity = resolveSeverity(count);

        ObjectNode extra = JsonNodeFactory.instance.objectNode();
        extra.put("count", count);
        extra.put("mediumThreshold", mediumThreshold);
        extra.put("highThreshold", highThreshold);
        // 升级标记：规范「切屏超过阈值」场景要求记录（基础级=低而实际更高即视为升级）
        extra.put("escalated", severity != baseSeverity());
        return EventVerdict.of(severity, shouldWarn(), extra);
    }

    /** 按累计次数与可配置阈值定级：先判高档再判中档，阈值 <=0 视为关闭该档。 */
    private SeverityLevel resolveSeverity(long count) {
        if (highThreshold > 0 && count >= highThreshold) {
            return SeverityLevel.HIGH;
        }
        if (mediumThreshold > 0 && count >= mediumThreshold) {
            return SeverityLevel.MEDIUM;
        }
        return baseSeverity();
    }
}
