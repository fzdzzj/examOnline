package com.exam.anticheat.service;

import com.exam.anticheat.collector.BehaviorEventRegistry;
import com.exam.anticheat.collector.BehaviorEventTypes;
import com.exam.anticheat.collector.CopyPasteEventCollector;
import com.exam.anticheat.collector.DraftConflictEventCollector;
import com.exam.anticheat.collector.FullScreenExitEventCollector;
import com.exam.anticheat.collector.PageRefreshEventCollector;
import com.exam.anticheat.collector.SubmitAnomalyEventCollector;
import com.exam.anticheat.collector.SwitchScreenEventCollector;
import com.exam.anticheat.collector.UnknownEventCollector;
import com.exam.anticheat.collector.WindowBlurEventCollector;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.monitoring.metrics.BusinessMetrics;
import com.exam.taking.service.ExamBehaviorLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 行为上报加固单元测试（change harden-behavior-report-and-monitor，spec-delta「行为上报防刷与明细上限」）：
 *
 * <p>覆盖采集核心统一通道上的两条服务端闸门——
 * <ol>
 *   <li><b>明细体积上限</b>：eventData 序列化字节数 &gt; 4096 时整笔拒绝（400「事件明细过大」），
 *       且拒绝必须发生在<b>策略判定之前</b>：不落库、不打点（超限请求不得在 exam_behavior_logs
 *       留下 TEXT 行，也不得因客户端可控 eventType 建出指标 tag）；</li>
 *   <li><b>时间戳服务端锚定</b>：occurredTime 与服务端 now 偏差绝对值 &gt; 10 分钟时按服务端 now
 *       落库（不拒绝）；容差内（含边界 10 分钟）原样保留客户端时间。</li>
 * </ol>
 *
 * <p>既有语义红线同样在此锁定：未注册事件类型照常落库（兜底策略），本闸门不得改变它。
 * 纯内存测试（mock 注册表依赖），不启动 Spring。
 */
class BehaviorEventCollectServiceTest {

    /** 与实现常量 MAX_EVENT_DATA_BYTES 对齐的上限（断言写在测试侧，改常量即变红）。 */
    private static final int MAX_BYTES = 4096;
    /** 与实现常量 MAX_CLOCK_SKEW 对齐的偏差阈值。 */
    private static final long MAX_SKEW_MINUTES = 10;

    private final ObjectMapper mapper = new ObjectMapper();

    private BehaviorEventRegistry registry() {
        UnknownEventCollector fallback = new UnknownEventCollector();
        return new BehaviorEventRegistry(
                List.of(new SwitchScreenEventCollector(mock(BehaviorCounterService.class)),
                        new WindowBlurEventCollector(), new PageRefreshEventCollector(),
                        new SubmitAnomalyEventCollector(), new DraftConflictEventCollector(),
                        new CopyPasteEventCollector(), new FullScreenExitEventCollector()),
                fallback);
    }

    private ExamBehaviorLogService logService() {
        return mock(ExamBehaviorLogService.class);
    }

    private BusinessMetrics metrics() {
        return mock(BusinessMetrics.class);
    }

    /** 构造一个「序列化后恰好 targetBytes 字节」的 ObjectNode：padding 字段独占体积。 */
    private ObjectNode payloadOfExactBytes(int targetBytes) {
        ObjectNode node = mapper.createObjectNode();
        node.put("v", "x");
        // 键序无关的自校验：先按 target-24 估长（{'v':'x','padding':''} = 22 字节 + 2 引号），
        // 再按实测长度微调，保证返回的节点序列化结果严格等于 targetBytes。
        node.put("padding", "a".repeat(Math.max(targetBytes - 24, 0)));
        int actual = serializedBytes(node);
        if (actual != targetBytes) {
            int padding = Math.max(node.get("padding").asText().length() + (targetBytes - actual), 0);
            node.put("padding", "a".repeat(padding));
            actual = serializedBytes(node);
        }
        assertEquals(targetBytes, actual, "夹具自校验：明细序列化长度必须精确等于目标字节数");
        return node;
    }

    private int serializedBytes(JsonNode node) {
        try {
            return mapper.writeValueAsBytes(node).length;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== ① eventData 体积上限 ====================

    @Test
    @DisplayName("边界即上限：序列化恰好 4096 字节放行落库（byte 口径，不是字符数）")
    void eventDataAtLimitIsAccepted() {
        ObjectNode eventData = payloadOfExactBytes(MAX_BYTES);
        assertEquals(MAX_BYTES, serializedBytes(eventData), "夹具前置：明细序列化须恰好等于上限");
        ExamBehaviorLogService logService = logService();
        BusinessMetrics metrics = metrics();
        BehaviorEventCollectService core =
                new BehaviorEventCollectService(registry(), logService, metrics, mapper);

        core.collect(1L, 2L, BehaviorEventTypes.SWITCH_SCREEN, eventData, LocalDateTime.now());

        // 落库明细 = 客户端明细 ∪ 策略补充字段（切屏策略会补 count 等），故不比对整串，
        // 只断言：①确实落库；②客户端原始字段被保留（上限内的明细不得被丢弃/截断）。
        JsonNode recorded = capturedEventData(logService);
        assertTrue(recorded.has("v") && "x".equals(recorded.get("v").asText()),
                "上限内明细的客户端字段必须原样带上：" + recorded);
        assertTrue(recorded.has("padding") && recorded.get("padding").asText().length() > MAX_BYTES - 32,
                "上限内明细不得被截断（padding 长度近似等于上限）");
        verify(metrics).countAntiCheatEvent(BehaviorEventTypes.SWITCH_SCREEN);
    }

    @Test
    @DisplayName("超限整笔拒绝：4097 字节抛 400「事件明细过大」，不落库不打点")
    void oversizedEventDataRejectedWithoutPersistNorMetric() {
        ObjectNode eventData = payloadOfExactBytes(MAX_BYTES + 1);
        assertEquals(MAX_BYTES + 1, serializedBytes(eventData), "夹具前置：明细序列化须恰好超限 1 字节");
        ExamBehaviorLogService logService = logService();
        BusinessMetrics metrics = metrics();
        BehaviorEventCollectService core =
                new BehaviorEventCollectService(registry(), logService, metrics, mapper);

        Executable reject = () -> core.collect(1L, 2L, BehaviorEventTypes.SWITCH_SCREEN,
                eventData, LocalDateTime.now());

        BusinessException e = assertThrows(BusinessException.class, reject, "超限明细必须整笔拒绝（非静默截断）");
        assertEquals(ResponseCode.BAD_REQUEST.getCode(), e.getCode(), "拒绝语义为 400（调用方错误，显式上抛）");
        assertEquals("事件明细过大", e.getMessage());
        verifyNoInteractions(logService);
        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("超限先于策略判定：未注册类型 + 超限明细同样不落库不打点（闸门在最前）")
    void oversizeCheckPrecedesDispatchSoUnknownTypeAlsoRejected() {
        ObjectNode eventData = mapper.createObjectNode();
        eventData.put("padding", "b".repeat(8192));
        ExamBehaviorLogService logService = logService();
        BusinessMetrics metrics = metrics();
        BehaviorEventCollectService core =
                new BehaviorEventCollectService(registry(), logService, metrics, mapper);

        assertThrows(BusinessException.class,
                () -> core.collect(1L, 2L, "SOME_FUTURE_EVENT", eventData, LocalDateTime.now()));

        // 兜底策略「照常落库」的前提是明细合法；超限请求连分派都不该发生
        verifyNoInteractions(logService);
        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("上限按 UTF-8 字节计：字符数未超但字节数超（中文明细）同样拒绝")
    void sizeLimitCountsUtf8BytesNotCharacters() {
        // 中文每字 3 字节：1400 字 = 4200 字节 > 4096，而字符数远小于上限
        ObjectNode eventData = mapper.createObjectNode();
        eventData.put("remark", "考".repeat(1400));
        int bytes = serializedBytes(eventData);
        assertTrue(bytes > MAX_BYTES && bytes < MAX_BYTES * 2,
                "夹具前置：字节数超限但字符数不超，字节数=" + bytes);
        ExamBehaviorLogService logService = logService();
        BehaviorEventCollectService core =
                new BehaviorEventCollectService(registry(), logService, metrics(), mapper);

        assertThrows(BusinessException.class,
                () -> core.collect(1L, 2L, BehaviorEventTypes.COPY_PASTE, eventData, LocalDateTime.now()));
        verifyNoInteractions(logService);
    }

    @Test
    @DisplayName("明细可空：null 走原路径落库（体积闸门不得把正常空明细挡在门外）")
    void nullEventDataStillCollected() {
        ExamBehaviorLogService logService = logService();
        BehaviorEventCollectService core =
                new BehaviorEventCollectService(registry(), logService, metrics(), mapper);

        core.collect(1L, 2L, BehaviorEventTypes.WINDOW_BLUR, null, LocalDateTime.now());

        verify(logService).record(eq(1L), eq(2L), eq(BehaviorEventTypes.WINDOW_BLUR),
                eq((JsonNode) null), anyInt(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("红线保全：未注册类型 + 合法明细照常落库（兜底策略不因新闸门改变）")
    void unknownTypeWithValidDataStillPersisted() {
        ExamBehaviorLogService logService = logService();
        BusinessMetrics metrics = metrics();
        BehaviorEventCollectService core =
                new BehaviorEventCollectService(registry(), logService, metrics, mapper);

        core.collect(1L, 2L, "SOME_FUTURE_EVENT",
                mapper.createObjectNode().put("action", "paste"), LocalDateTime.now());

        verify(logService).record(eq(1L), eq(2L), eq("SOME_FUTURE_EVENT"),
                any(JsonNode.class), anyInt(), any(LocalDateTime.class));
        verify(metrics).countAntiCheatEvent("SOME_FUTURE_EVENT");
    }

    // ==================== ② occurredTime 服务端锚定 ====================

    @Test
    @DisplayName("时间戳锚定：过去 30 分钟按服务端 now 落库（不拒绝）")
    void staleOccurredTimeAnchoredToServerNow() {
        LocalDateTime stale = LocalDateTime.now().minusMinutes(30);
        assertAnchoredToServerNow(stale);
    }

    @Test
    @DisplayName("时间戳锚定：未来 30 分钟按服务端 now 落库（防时间线排序被提前塞入）")
    void futureOccurredTimeAnchoredToServerNow() {
        LocalDateTime future = LocalDateTime.now().plusMinutes(30);
        assertAnchoredToServerNow(future);
    }

    private void assertAnchoredToServerNow(LocalDateTime clientTime) {
        ExamBehaviorLogService logService = logService();
        BehaviorEventCollectService core =
                new BehaviorEventCollectService(registry(), logService, metrics(), mapper);
        LocalDateTime before = LocalDateTime.now().minusSeconds(2);

        core.collect(1L, 2L, BehaviorEventTypes.SWITCH_SCREEN, null, clientTime);

        LocalDateTime recorded = capturedEventTime(logService);
        LocalDateTime after = LocalDateTime.now().plusSeconds(2);
        assertTrue(!recorded.isBefore(before) && !recorded.isAfter(after),
                "偏差超阈值时落库时间必须锚定服务端 now，实际=" + recorded + "（客户端声称=" + clientTime + "）");
    }

    @Test
    @DisplayName("时间戳容差：偏差 9 分 59 秒（阈值内一侧）保留客户端时间，不锚定")
    void occurredTimeWithinThresholdKeptAsIs() {
        // 距阈值仅 1 秒：既稳定落在容差内（测试内 now() 与实现内 now() 不可能差 1 秒），
        // 又紧贴边界——阈值被放大/去掉锚定逻辑都会立刻偏离客户端时间而变红。
        LocalDateTime justInside = LocalDateTime.now().minusMinutes(MAX_SKEW_MINUTES).plusSeconds(1)
                .truncatedTo(ChronoUnit.SECONDS);
        ExamBehaviorLogService logService = logService();
        BehaviorEventCollectService core =
                new BehaviorEventCollectService(registry(), logService, metrics(), mapper);

        core.collect(1L, 2L, BehaviorEventTypes.SWITCH_SCREEN, null, justInside);

        assertKeptWithinOneSecond(justInside, capturedEventTime(logService),
                "阈值内一侧的客户端时间应原样落库——正常时钟漂移不得被改写");
    }

    @Test
    @DisplayName("时间戳锚定：偏差 10 分 1 秒（刚过阈值）按服务端 now 落库")
    void occurredTimeJustBeyondThresholdAnchored() {
        LocalDateTime justBeyond = LocalDateTime.now().minusMinutes(MAX_SKEW_MINUTES).minusSeconds(1);
        ExamBehaviorLogService logService = logService();
        BehaviorEventCollectService core =
                new BehaviorEventCollectService(registry(), logService, metrics(), mapper);
        LocalDateTime before = LocalDateTime.now().minusSeconds(2);

        core.collect(1L, 2L, BehaviorEventTypes.SWITCH_SCREEN, null, justBeyond);

        LocalDateTime recorded = capturedEventTime(logService);
        LocalDateTime after = LocalDateTime.now().plusSeconds(2);
        assertTrue(!recorded.isBefore(before) && !recorded.isAfter(after),
                "刚过阈值即锚定，实际=" + recorded);
    }

    @Test
    @DisplayName("时间戳容差：过去 9 分钟（容差中段）保留客户端时间（正常漂移不改写）")
    void occurredTimeWellWithinThresholdKept() {
        LocalDateTime recent = LocalDateTime.now().minusMinutes(9).truncatedTo(ChronoUnit.SECONDS);
        ExamBehaviorLogService logService = logService();
        BehaviorEventCollectService core =
                new BehaviorEventCollectService(registry(), logService, metrics(), mapper);

        core.collect(1L, 2L, BehaviorEventTypes.SWITCH_SCREEN, null, recent);

        assertKeptWithinOneSecond(recent, capturedEventTime(logService), "9 分钟偏差属正常漂移，不得改写");
    }

    @Test
    @DisplayName("occurredTime 缺省：null 时落库时间为服务端当前时间")
    void nullOccurredTimeUsesServerNow() {
        ExamBehaviorLogService logService = logService();
        BehaviorEventCollectService core =
                new BehaviorEventCollectService(registry(), logService, metrics(), mapper);
        LocalDateTime before = LocalDateTime.now().minusSeconds(2);

        core.collect(1L, 2L, BehaviorEventTypes.SWITCH_SCREEN, null, null);

        LocalDateTime recorded = capturedEventTime(logService);
        LocalDateTime after = LocalDateTime.now().plusSeconds(2);
        assertTrue(!recorded.isBefore(before) && !recorded.isAfter(after),
                "缺省发生时间应为服务端 now，实际=" + recorded);
    }

    /**
     * 断言「客户端时间被原样保留」：秒级相等即可——客户端值已截到秒，
     * 而服务端只看秒级比较（{@code ChronoUnit.SECONDS}），纳秒尾数不属于契约；
     * 若实现改为锚定服务端 now，则落库值与客户端值相差分钟级，本断言立即变红。
     */
    private void assertKeptWithinOneSecond(LocalDateTime expected, LocalDateTime recorded, String message) {
        assertTrue(expected.truncatedTo(ChronoUnit.SECONDS)
                        .equals(recorded.truncatedTo(ChronoUnit.SECONDS)),
                message + "：客户端=" + expected + " 落库=" + recorded);
    }

    /** 取落库实参中的 eventData（ArgumentCaptor，避开同名 any 匹配的歧义）。 */
    private JsonNode capturedEventData(ExamBehaviorLogService logService) {
        ArgumentCaptor<JsonNode> dataCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(logService).record(anyLong(), anyLong(), any(), dataCaptor.capture(),
                anyInt(), any(LocalDateTime.class));
        return dataCaptor.getValue();
    }

    /** 取落库实参中的 eventTime（ArgumentCaptor，避免同名 any 匹配的歧义）。 */
    private LocalDateTime capturedEventTime(ExamBehaviorLogService logService) {
        ArgumentCaptor<LocalDateTime> timeCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(logService).record(anyLong(), anyLong(), any(), ArgumentMatchers.<JsonNode>any(), anyInt(), timeCaptor.capture());
        return timeCaptor.getValue();
    }
}
