package com.exam.anticheat.service;

import com.exam.anticheat.collector.BehaviorEventCollector;
import com.exam.anticheat.collector.BehaviorEventRegistry;
import com.exam.anticheat.model.BehaviorEventContext;
import com.exam.anticheat.model.EventVerdict;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.monitoring.metrics.BusinessMetrics;
import com.exam.taking.service.ExamBehaviorLogService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 行为事件采集核心（spec add-anti-cheat「行为事件采集」需求——"新增事件类型 SHALL 不改变采集核心"）：
 *
 * <p>核心流程固定为四步，不理解任何具体事件语义：
 * <ol>
 *   <li><b>校验</b>：明细体积上限 + 发生时间服务端锚定（与事件语义无关的统一闸门，见 {@link #collect}）；</li>
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

    /**
     * 单笔事件明细序列化字节上限（change harden-behavior-report-and-monitor）：
     * 4096 字节。正常明细只有离开时长/客户端时间/违规动作等几个短字段（数十字节量级），
     * 上限留两个数量级余量；而落库列是 TEXT（64KB），无上限时脚本可无限刷写存储与查询。
     */
    static final int MAX_EVENT_DATA_BYTES = 4096;

    /** 客户端发生时间与服务端时钟的允许偏差（超过即按服务端 now 锚定，容忍正常时钟漂移/NTP 抖动）。 */
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(10);

    /** 偏差阈值（分钟口径，仅用于日志可读性）。 */
    static final long MAX_CLOCK_SKEW_MINUTES = 10;

    /** 超限拒绝文案（spec-delta 固定口径，前端依此提示）。 */
    static final String EVENT_DATA_TOO_LARGE_MESSAGE = "事件明细过大";

    /** 明细不可序列化时的拒绝文案（同属调用方参数错误）。 */
    static final String EVENT_DATA_INVALID_MESSAGE = "事件明细格式非法";

    private final BehaviorEventRegistry registry;
    private final ExamBehaviorLogService behaviorLogService;
    private final BusinessMetrics metrics;
    private final ObjectMapper objectMapper;

    public BehaviorEventCollectService(BehaviorEventRegistry registry, ExamBehaviorLogService behaviorLogService,
                                       BusinessMetrics metrics, ObjectMapper objectMapper) {
        this.registry = registry;
        this.behaviorLogService = behaviorLogService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /**
     * 采集一次行为事件：参数校验 → 分派策略 → 判定 → 落库，返回判定结果供调用方使用
     * （如切屏警告提示需要 severity/warn/count）。
     *
     * <p><b>统一通道上的两道闸门（change harden-behavior-report-and-monitor）：</b>
     * <ol>
     *   <li><b>明细体积上限</b>（{@value #MAX_EVENT_DATA_BYTES} 字节，UTF-8 序列化口径）：
     *       超限抛 400「事件明细过大」整笔拒绝。校验刻意放在本方法<b>最前</b>——先于分派与判定，
     *       故超限请求既不落库（TEXT 列 64KB/行，脚本可无限刷写）、也不打点
     *       （客户端可控 eventType 不得借超限请求建指标 tag）。
     *       注意：这是<b>调用方参数错误</b>，与下方"策略判定/落库异常内部消化"的旁路承诺不冲突——
     *       旁路承诺覆盖的是判定与落库段，参数拒绝必须显式上抛（否则前端永远不知道上报没生效）；</li>
     *   <li><b>发生时间服务端锚定</b>：客户端可写任意过去/未来 occurredTime 污染时间线排序
     *       （event_time 是时间线与监考大屏异常排序的口径源），偏差绝对值超过
     *       {@value #MAX_CLOCK_SKEW_MINUTES} 分钟即按服务端 now 落库——只锚定，不拒绝
     *       （容差内的正常时钟漂移原样保留）。</li>
     * </ol>
     *
     * @param examId       考试 ID
     * @param studentId    学生 ID
     * @param eventType    事件类型（见 {@code BehaviorEventTypes}；未注册类型走兜底策略照常落库）
     * @param eventData    客户端/调用方上报的事件明细（可空；策略补充字段与其合并后落库）
     * @param occurredTime 事件发生时间（可空，缺省服务端当前时间）
     * @return 判定结果（永不返回 null）；判定与落库段内部已消化全部异常，调用方无需 try-catch
     * @throws BusinessException 400「事件明细过大」——明细序列化超过 {@value #MAX_EVENT_DATA_BYTES} 字节
     */
    public EventVerdict collect(Long examId, Long studentId, String eventType,
                                JsonNode eventData, LocalDateTime occurredTime) {
        requireEventDataWithinLimit(eventData);
        LocalDateTime eventTime = anchorToServerClock(occurredTime);
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
        // 可观测性打点：在此统一通道按事件类型计数（新增事件类型自动纳管，核心零改动；
        // 未注册类型由 BusinessMetrics 归并为固定 tag，指标基数有界）
        metrics.countAntiCheatEvent(eventType);
        return verdict;
    }

    /**
     * 明细体积闸门：序列化字节数（UTF-8，与落库的 {@code JsonNode.toString()} 同源）超过
     * {@value #MAX_EVENT_DATA_BYTES} 字节即整笔拒绝。
     *
     * <p>按字节而非字符数计：中文等多字节字符下字符数会低估 3～4 倍，而落库列是字节口径的 TEXT。
     */
    private void requireEventDataWithinLimit(JsonNode eventData) {
        if (eventData == null || eventData.isNull()) {
            return; // 无明细是正常场景（如纯切屏事件），不参与体积判定
        }
        int bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(eventData).length;
        } catch (JsonProcessingException e) {
            // 明细不可序列化 = 调用方数据有问题，按非法参数拒绝（不落库），不静默丢弃
            log.warn("事件明细序列化失败，按非法明细拒绝: {}", e.getMessage());
            throw new BusinessException(ResponseCode.BAD_REQUEST, EVENT_DATA_INVALID_MESSAGE);
        }
        if (bytes > MAX_EVENT_DATA_BYTES) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, EVENT_DATA_TOO_LARGE_MESSAGE);
        }
    }

    /** 时间锚定：可空 → 服务端 now；偏差超阈值 → 服务端 now；容差内 → 原样保留客户端时间。 */
    private LocalDateTime anchorToServerClock(LocalDateTime occurredTime) {
        LocalDateTime now = LocalDateTime.now();
        if (occurredTime == null) {
            return now;
        }
        Duration skew = Duration.between(now, occurredTime).abs();
        if (skew.compareTo(MAX_CLOCK_SKEW) > 0) {
            log.warn("行为事件发生时间偏差 {} 分钟超阈值，按服务端时间锚定: 客户端={} 服务端={}",
                    skew.toMinutes(), occurredTime, now);
            return now;
        }
        return occurredTime;
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
