package com.exam.anticheat.collector;

import com.exam.anticheat.model.BehaviorEventContext;
import com.exam.anticheat.model.EventVerdict;
import com.exam.anticheat.model.SeverityLevel;
import org.springframework.stereotype.Component;

/**
 * 复制粘贴事件策略（spec add-anti-cheat-restrictions「采集复制粘贴违规事件」场景）：
 * 考试开启 {@code forbidCopy} 时，作答端拦截 copy/cut/paste/contextmenu 并旁路上报本事件。
 *
 * <p>基础严重度"中"：与误触切屏不同，复制粘贴通常是<b>主动</b>把外部答案灌入答题框的行为，
 * 比刷新更值得教师留意（与 {@link PageRefreshEventCollector} 同级）；
 * 但同样只警告 + 记录，<b>绝不强制交卷</b>——是否处置由教师依行为日志判定（决策记录 §3.1）。
 * 事件明细由前端上报（action=copy/cut/paste 等），策略不追加计数升级。
 */
@Component
public class CopyPasteEventCollector implements BehaviorEventCollector {

    @Override
    public String eventType() {
        return BehaviorEventTypes.COPY_PASTE;
    }

    @Override
    public SeverityLevel baseSeverity() {
        return SeverityLevel.MEDIUM;
    }

    @Override
    public boolean shouldWarn() {
        // 提示学生"本场禁止复制粘贴"（前端弹友好提示），仅提示不处置
        return true;
    }

    @Override
    public EventVerdict judge(BehaviorEventContext context) {
        // 无次数升级：基础"中"级 + 警告标记。
        // 注意：接口默认 judge 只带严重度（warn=false），要下发警告必须显式构造判定
        return EventVerdict.of(baseSeverity(), shouldWarn(), null);
    }
}
