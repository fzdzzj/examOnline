package com.exam.anticheat.service;

import com.exam.anticheat.collector.BehaviorEventCollector;
import com.exam.anticheat.collector.BehaviorEventRegistry;
import com.exam.anticheat.model.BehaviorEventContext;
import com.exam.anticheat.model.EventVerdict;
import com.exam.monitoring.metrics.BusinessMetrics;
import com.exam.taking.service.ExamBehaviorLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 行为事件采集核心（spec add-anti-cheat「行为事件采集」需求——"新增事件类型 SHALL 不改变采集核心"）：
 *
 * <p>核心流程固定为三步，不理解任何具体事件语义：
 * <ol>
 *   <li><b>分派</b>：按事件类型从 {@link BehaviorEventRegistry} 取策略（未注册类型走兜底）；</li>
 *   <li><b>判定</b>：调策略 {@code judge} 得到严重度/警告/补充明细——事件规则全部封装在策略内；</li>
 *   <li><b>落库</b>：写 exam_behavior_logs（event_type/event_data/severity/event_time），
 *       复用阶段 5 建的表（不重复建表）与 {@link ExamBehaviorLogService} 落库通道。</li>
 * </ol>
 *
 * <p>因此新增一种事件类型的全部工作量 = 新增一个策略实现类（@Component 即注册），
 * 本核心一行不改——与判分侧"新增题型不改判分核心"互为呼应。
 *
 * <p>旁路原则：策略判定的任何异常都在本服务内消化（降级为基础严重度照常落库），
 * 落库失败由阶段 5 的 ExamBehaviorLogService 只记日志不上抛——
 * 行为采集绝不阻断学生答题主链路（spec「切屏仅记录」的工程前提）。
 */
@Slf4j
@Service
public class BehaviorEventCollectService {

    private final BehaviorEventRegistry registry;
    private final ExamBehaviorLogService behaviorLogService;
    private final BusinessMetrics metrics;

    public BehaviorEventCollectService(BehaviorEventRegistry registry, ExamBehaviorLogService behaviorLogService,
                                       BusinessMetrics metrics) {
        this.registry = registry;
        this.behaviorLogService = behaviorLogService;
        this.metrics = metrics;
    }

    /**
     * 采集一次行为事件：分派策略 → 判定 → 落库，返回判定结果供调用方使用
     * （如切屏警告提示需要 severity/warn/count）。
     *
     * @param examId       考试 ID
     * @param studentId    学生 ID
     * @param eventType    事件类型（见 {@code BehaviorEventTypes}；未注册类型走兜底策略照常落库）
     * @param eventData    客户端/调用方上报的事件明细（可空；策略补充字段与其合并后落库）
     * @param occurredTime 事件发生时间（可空，缺省服务端当前时间）
     * @return 判定结果（永不返回 null）；内部已消化全部异常，调用方无需 try-catch
     */
    @Async("monitorExecutor")
    public EventVerdict collect(Long examId, Long studentId, String eventType,
                                JsonNode eventData, LocalDateTime occurredTime) {
        LocalDateTime eventTime = occurredTime == null ? LocalDateTime.now() : occurredTime;
        BehaviorEventCollector collector = registry.dispatch(eventType);
        BehaviorEventContext context = new BehaviorEventContext(examId, studentId, eventData, eventTime);

        // 判定兜底：策略自身异常（如 Redis 抖动）降级为基础严重度，事件仍被记录
        EventVerdict verdict;
        try {
            verdict = collector.judge(context);
        } catch (Exception e) {
            log.error("事件策略判定异常，降级为基础严重度: exam={} student={} type={}",
                    examId, studentId, eventType, e);
            verdict = EventVerdict.of(collector.baseSeverity());
        }

        behaviorLogService.record(examId, studentId, eventType,
                merge(eventData, verdict.extraData()), verdict.severity().code(), eventTime);
        // 可观测性打点：在此统一通道按事件类型计数（新增事件类型自动纳管，核心零改动）
        metrics.countAntiCheatEvent(eventType);
        return verdict;
    }

    /** 客户端明细与策略补充明细合并（策略补充字段优先，如切屏次数/升级标记）。 */
    private JsonNode merge(JsonNode clientData, JsonNode extraData) {
        if (!(extraData instanceof ObjectNode extras) || extras.isEmpty()) {
            return clientData;
        }
        ObjectNode merged = JsonNodeFactory.instance.objectNode();
        if (clientData instanceof ObjectNode clientObject) {
            merged.setAll(clientObject);
        }
        merged.setAll(extras);
        return merged;
    }
}
