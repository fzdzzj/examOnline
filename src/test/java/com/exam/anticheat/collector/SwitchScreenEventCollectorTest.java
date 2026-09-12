package com.exam.anticheat.collector;

import com.exam.anticheat.model.BehaviorEventContext;
import com.exam.anticheat.model.EventVerdict;
import com.exam.anticheat.model.SeverityLevel;
import com.exam.anticheat.service.BehaviorCounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 切屏事件策略单元测试（spec「切屏超过阈值」「切屏警告不交卷」场景）：
 * 纯内存 Mockito 桩验证阈值升级算法——阈值可配置、升级有标记、只警告不交卷。
 */
@ExtendWith(MockitoExtension.class)
class SwitchScreenEventCollectorTest {

    private static final long EXAM_ID = 1L;
    private static final long STUDENT_ID = 100L;

    @Mock
    private BehaviorCounterService counter;

    private SwitchScreenEventCollector collector;

    private final LocalDateTime now = LocalDateTime.now();

    @BeforeEach
    void setUp() {
        collector = new SwitchScreenEventCollector(counter);
        // 默认阈值（与 application.yml 保持一致）：3 升中、5 升高
        ReflectionTestUtils.setField(collector, "mediumThreshold", 3);
        ReflectionTestUtils.setField(collector, "highThreshold", 5);
    }

    private EventVerdict judgeWithCount(long count) {
        when(counter.increment(eq(EXAM_ID), eq(STUDENT_ID), eq("SWITCH_SCREEN"))).thenReturn(count);
        return collector.judge(new BehaviorEventContext(EXAM_ID, STUDENT_ID, null, now));
    }

    @Test
    @DisplayName("切屏阈值升级：1-2次低、3-4次中、5次及以上高（阈值可配置）")
    void severityEscalationByCount() {
        assertEquals(SeverityLevel.LOW, judgeWithCount(1).severity());
        assertEquals(SeverityLevel.LOW, judgeWithCount(2).severity());
        assertEquals(SeverityLevel.MEDIUM, judgeWithCount(3).severity());
        assertEquals(SeverityLevel.MEDIUM, judgeWithCount(4).severity());
        assertEquals(SeverityLevel.HIGH, judgeWithCount(5).severity());
        assertEquals(SeverityLevel.HIGH, judgeWithCount(6).severity());
    }

    @Test
    @DisplayName("升级标记：达到阈值后 extraData.escalated=true 且携带当前次数")
    void escalationMarkedInExtraData() {
        EventVerdict first = judgeWithCount(1);
        assertFalse(first.extraData().get("escalated").asBoolean());
        assertEquals(1, first.extraData().get("count").asInt());

        EventVerdict escalated = judgeWithCount(3);
        assertTrue(escalated.extraData().get("escalated").asBoolean(), "达到中阈值应标记升级");
        assertEquals(3, escalated.extraData().get("count").asInt());
        assertEquals(3, escalated.extraData().get("mediumThreshold").asInt());
        assertEquals(5, escalated.extraData().get("highThreshold").asInt());
    }

    @Test
    @DisplayName("每次切屏都警告（warn=true）——警告不等于强制交卷，策略无任何交卷路径")
    void alwaysWarnButNeverForceSubmit() {
        EventVerdict verdict = judgeWithCount(1);
        assertTrue(verdict.warn());
        // 回归确认：策略接口没有交卷方法，EventVerdict 只含 severity/warn/extraData
        EventVerdict severe = judgeWithCount(10);
        assertTrue(severe.warn());
        assertEquals(SeverityLevel.HIGH, severe.severity());
    }

    @Test
    @DisplayName("阈值配置为 0 时关闭对应档位升级")
    void zeroThresholdDisablesEscalation() {
        ReflectionTestUtils.setField(collector, "mediumThreshold", 0);
        ReflectionTestUtils.setField(collector, "highThreshold", 0);
        assertEquals(SeverityLevel.LOW, judgeWithCount(100).severity());
    }

    @Test
    @DisplayName("计数器 Redis 异常降级：按首次事件处理（低级），采集不中断")
    void counterFailureDegradesToFirstEvent() {
        // 真实计数器 + Redis 桩抛异常，验证 increment 内部降级逻辑
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString()))
                .thenThrow(new IllegalStateException("redis down"));

        com.exam.anticheat.service.BehaviorCounterService realCounter =
                new com.exam.anticheat.service.BehaviorCounterService(redisTemplate);
        ReflectionTestUtils.setField(realCounter, "ttlHours", 12);

        assertEquals(1L, realCounter.increment(EXAM_ID, STUDENT_ID, "SWITCH_SCREEN"));
    }

    @Test
    @DisplayName("计数 key 口径：按 考试:学生:事件类型 隔离")
    void counterKeyIsolation() {
        when(counter.increment(any(), any(), anyString())).thenReturn(1L);
        collector.judge(new BehaviorEventContext(7L, 8L, null, now));
        org.mockito.Mockito.verify(counter).increment(eq(7L), eq(8L), eq("SWITCH_SCREEN"));
    }
}
