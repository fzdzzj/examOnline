package com.exam.anticheat.collector;

import com.exam.anticheat.model.BehaviorEventContext;
import com.exam.anticheat.model.EventVerdict;
import com.exam.anticheat.model.SeverityLevel;

/**
 * 行为事件采集策略（面试弹药 A「策略模式事件体系」的代码载体，spec add-anti-cheat
 * 「行为事件采集」需求——与判分侧 {@code GradingStrategy} 同构，形成两处呼应）：
 *
 * <p>为什么用策略模式——各类事件的"严重度怎么定、要不要警告、明细补什么"差异极大
 * （切屏要数次数做阈值升级、交卷异常一步到位严重级、刷新/失焦只需基础级），
 * if-else 分支会让采集核心随事件类型膨胀且每次加事件都要重改重测核心；
 * 策略接口把"分派"与"事件规则"解耦：<b>新增事件类型 = 新增一个实现类并标注 @Component，
 * Spring 自动装配进注册中心，采集核心（分派→判定→落库）零改动</b>。
 *
 * <p>约定：实现必须无状态可单例复用（历史计数等状态一律外置 Redis，
 * 见切屏策略的计数器），判定失败由采集核心兜底为基础级，不允许向上抛异常
 * （行为采集是旁路，绝不阻断答题主链路）。
 */
public interface BehaviorEventCollector {

    /** 本策略负责的事件类型（注册与分派依据，同 {@code GradingStrategy#questionType()}）。 */
    String eventType();

    /**
     * 本事件的基础严重度：无历史上下文（如该学生第一次切屏）时的默认分级。
     * 动态升级逻辑（如切屏次数阈值）在 {@link #judge} 内叠加，不写死在核心。
     */
    SeverityLevel baseSeverity();

    /**
     * 判定一次事件的最终严重度与警告标记。
     *
     * <p>默认实现即"按基础严重度放行"，绝大多数事件无需覆盖；
     * 需要历史上下文的策略（如切屏次数升级）覆写本方法补充逻辑。
     *
     * @param context 事件上下文（考试/学生/明细/时间）
     * @return 判定结果；实现不得返回 null，不得抛出异常
     */
    default EventVerdict judge(BehaviorEventContext context) {
        return EventVerdict.of(baseSeverity());
    }

    /**
     * 是否需要前端弹警告提示（切屏/失焦 true——spec「切屏警告不交卷」场景：
     * 只警告并记录，绝不强制交卷；是否处置由教师事后依行为日志判定）。
     */
    default boolean shouldWarn() {
        return false;
    }
}
