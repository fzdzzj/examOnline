package com.exam.anticheat.collector;

import com.exam.anticheat.model.BehaviorEventContext;
import com.exam.anticheat.model.EventVerdict;
import com.exam.anticheat.model.SeverityLevel;
import com.exam.anticheat.service.BehaviorEventCollectService;
import com.exam.taking.service.ExamBehaviorLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 行为事件采集策略单元测试（spec add-anti-cheat「行为事件采集」——新增事件不动核心）：
 * 纯内存测试，不启动 Spring——策略必须无状态，直接 new 验证分派与采集核心装配。
 */
class BehaviorEventCollectorTest {

    private final UnknownEventCollector fallback = new UnknownEventCollector();

    private BehaviorEventRegistry registry() {
        return new BehaviorEventRegistry(
                List.of(new SwitchScreenEventCollector(), new WindowBlurEventCollector(),
                        new PageRefreshEventCollector(), new SubmitAnomalyEventCollector(),
                        new DraftConflictEventCollector()),
                fallback);
    }

    // ==================== 策略注册与分派 ====================

    @Test
    @DisplayName("注册中心：按事件类型分派到对应策略")
    void registryDispatch() {
        BehaviorEventRegistry registry = registry();
        assertInstanceOf(SwitchScreenEventCollector.class, registry.dispatch("SWITCH_SCREEN"));
        assertInstanceOf(WindowBlurEventCollector.class, registry.dispatch("WINDOW_BLUR"));
        assertInstanceOf(PageRefreshEventCollector.class, registry.dispatch("PAGE_REFRESH"));
        assertInstanceOf(SubmitAnomalyEventCollector.class, registry.dispatch("SUBMIT_ANOMALY"));
        assertInstanceOf(DraftConflictEventCollector.class, registry.dispatch("DRAFT_CONFLICT"));
    }

    @Test
    @DisplayName("注册中心：未注册类型走兜底策略（前向兼容，采集不拒绝）")
    void unknownTypeFallsBack() {
        BehaviorEventRegistry registry = registry();
        BehaviorEventCollector collector = registry.dispatch("SOME_FUTURE_EVENT");
        assertInstanceOf(UnknownEventCollector.class, collector);
        // 兜底策略：最低严重度、不警告
        EventVerdict verdict = collector.judge(new BehaviorEventContext(1L, 2L, null, LocalDateTime.now()));
        assertEquals(SeverityLevel.LOW, verdict.severity());
    }

    @Test
    @DisplayName("注册中心：同类型重复注册快速失败（装配事故不随机分派）")
    void duplicateRegistrationFails() {
        assertThrows(IllegalStateException.class,
                () -> new BehaviorEventRegistry(
                        List.of(new SwitchScreenEventCollector(), new SwitchScreenEventCollector()), fallback));
    }

    // ==================== 新增事件不动核心 ====================

    @Test
    @DisplayName("新增事件不动核心：测试内新造一个事件策略，采集核心零改动即可采集落库")
    void newEventTypeWithoutTouchingCore() {
        // 模拟"未来新增"的事件策略：无需修改采集核心/注册中心的任何代码，只要实现接口即可被装配
        BehaviorEventCollector newEvent = new BehaviorEventCollector() {
            @Override
            public String eventType() {
                return "CAMERA_COVER"; // 假想的新事件：遮挡摄像头
            }

            @Override
            public SeverityLevel baseSeverity() {
                return SeverityLevel.MEDIUM;
            }
        };
        BehaviorEventRegistry registry = new BehaviorEventRegistry(
                List.of(new SwitchScreenEventCollector(), newEvent), fallback);
        assertInstanceOf(newEvent.getClass(), registry.dispatch("CAMERA_COVER"));

        // 采集核心对新事件照常完成"判定 → 落库"全流程
        ExamBehaviorLogService logService = mock(ExamBehaviorLogService.class);
        BehaviorEventCollectService core = new BehaviorEventCollectService(registry, logService);
        EventVerdict verdict = core.collect(1L, 2L, "CAMERA_COVER", null, LocalDateTime.now());

        assertEquals(SeverityLevel.MEDIUM, verdict.severity());
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(logService).record(eq(1L), eq(2L), typeCaptor.capture(),
                ArgumentMatchers.<JsonNode>any(), eq(2), any(LocalDateTime.class));
        assertEquals("CAMERA_COVER", typeCaptor.getValue());
    }

    // ==================== 各事件基础严重度 ====================

    @Test
    @DisplayName("各事件基础严重度：切屏/失焦/草稿冲突低、刷新中、交卷异常高")
    void baseSeverities() {
        BehaviorEventRegistry registry = registry();
        LocalDateTime now = LocalDateTime.now();
        ObjectMapper mapper = new ObjectMapper();

        assertEquals(SeverityLevel.LOW, registry.dispatch("SWITCH_SCREEN")
                .judge(new BehaviorEventContext(1L, 2L, null, now)).severity());
        assertEquals(SeverityLevel.LOW, registry.dispatch("WINDOW_BLUR")
                .judge(new BehaviorEventContext(1L, 2L, null, now)).severity());
        assertEquals(SeverityLevel.LOW, registry.dispatch("DRAFT_CONFLICT")
                .judge(new BehaviorEventContext(1L, 2L, null, now)).severity());
        assertEquals(SeverityLevel.MEDIUM, registry.dispatch("PAGE_REFRESH")
                .judge(new BehaviorEventContext(1L, 2L, null, now)).severity());
        assertEquals(SeverityLevel.HIGH, registry.dispatch("SUBMIT_ANOMALY")
                .judge(new BehaviorEventContext(1L, 2L, null, now)).severity());
    }

    // ==================== 采集核心旁路原则 ====================

    @Test
    @DisplayName("采集核心：策略判定抛异常时降级为基础严重度，事件照常落库")
    void judgeExceptionFallsBackToBaseSeverity() {
        BehaviorEventCollector exploding = new BehaviorEventCollector() {
            @Override
            public String eventType() {
                return "EXPLODING_EVENT";
            }

            @Override
            public SeverityLevel baseSeverity() {
                return SeverityLevel.MEDIUM;
            }

            @Override
            public EventVerdict judge(BehaviorEventContext context) {
                throw new IllegalStateException("策略内部异常（如 Redis 抖动）");
            }
        };
        BehaviorEventRegistry registry = new BehaviorEventRegistry(List.of(exploding), fallback);
        ExamBehaviorLogService logService = mock(ExamBehaviorLogService.class);
        BehaviorEventCollectService core = new BehaviorEventCollectService(registry, logService);

        EventVerdict verdict = core.collect(1L, 2L, "EXPLODING_EVENT", null, null);

        // 异常被核心消化：降级为基础严重度 MEDIUM，落库继续
        assertEquals(SeverityLevel.MEDIUM, verdict.severity());
        verify(logService).record(eq(1L), eq(2L), eq("EXPLODING_EVENT"),
                ArgumentMatchers.<JsonNode>isNull(), eq(2), any(LocalDateTime.class));
    }
}
