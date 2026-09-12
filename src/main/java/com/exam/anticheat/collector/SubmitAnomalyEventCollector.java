package com.exam.anticheat.collector;

import com.exam.anticheat.model.SeverityLevel;
import org.springframework.stereotype.Component;

/**
 * 交卷异常事件策略（本阶段补齐的事件类型之一）：
 *
 * <p>采集点在交卷链路（{@code ExamSubmitService}）：交卷消息发送失败、答案转草稿
 * 兜底等待补发时，由服务端自行上报——与前端上报的切屏/失焦不同，这类事件
 * 天然只可能由服务端产生，是"事件来源不限于客户端"的例证。
 *
 * <p>基础严重度按"高"：交卷异常意味着学生答卷可能延迟落库，属于需要教师
 * 在监考大屏立即关注的系统级事件（答卷本身有三重幂等与补发自愈保障，
 * 此处只负责让异常被看见）。
 */
@Component
public class SubmitAnomalyEventCollector implements BehaviorEventCollector {

    @Override
    public String eventType() {
        return BehaviorEventTypes.SUBMIT_ANOMALY;
    }

    @Override
    public SeverityLevel baseSeverity() {
        return SeverityLevel.HIGH;
    }
}
