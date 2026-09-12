package com.exam.anticheat.collector;

import com.exam.anticheat.model.BehaviorEventContext;
import com.exam.anticheat.model.EventVerdict;
import com.exam.anticheat.model.SeverityLevel;
import org.springframework.stereotype.Component;

/**
 * 未知事件兜底策略：注册中心分派不到具体策略时的缺省实现。
 *
 * <p>为什么需要兜底而不是像判分注册中心那样抛异常——行为事件来自前端实时上报，
 * 存在"前端先于后端上线新事件类型"的窗口期；判分分派不到会导致成绩错误（必须快速失败），
 * 行为事件分派不到只丢一条日志（旁路数据），按最低严重度照常落库是更合理的降级：
 * 采集永远不拒绝、不报错、不阻断答题。
 */
@Component
public class UnknownEventCollector implements BehaviorEventCollector {

    @Override
    public String eventType() {
        // 兜底策略不占用具体类型键，仅供注册中心缺省分派
        return "";
    }

    @Override
    public SeverityLevel baseSeverity() {
        // 未知事件按最低级别记录，待后续版本为其配备正式策略
        return SeverityLevel.LOW;
    }

    @Override
    public EventVerdict judge(BehaviorEventContext context) {
        // 未知事件不警告、不升级，原样落库
        return EventVerdict.of(baseSeverity());
    }
}
