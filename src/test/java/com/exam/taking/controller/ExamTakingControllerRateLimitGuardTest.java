package com.exam.taking.controller;

import com.exam.common.ratelimit.RateLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 学生答题端写端点限流护栏（change harden-behavior-report-and-monitor，spec-delta「行为上报防刷与明细上限」）：
 *
 * <p>本控制器三个写端点此前只有 enter/submit 打了 {@code @RateLimit}，
 * 行为上报 {@code POST /api/exam-taking/exams/{examId}/behavior} 是唯一的裸写端点——
 * 学生可脚本无限刷写行为日志（每请求策略判定 + 单行 insert + 指标打点）。
 * 本测试以反射读注解的方式把「每个写端点都受令牌桶保护」钉成断言：
 * 摘掉注解、改动 qps/capacity 量级、或把参数写成默认值（注解存在但无实际阈值）都会变红。
 *
 * <p>量级论证（与代码注释同源）：千人大考正常情况下学生偶发切屏/失焦/粘贴，
 * 峰值远低于 200/s；单次处理轻（判定 + 单行 insert + counter），故取值介于
 * enter（2000/5000，拉卷尖峰）与 submit（500/2000，单次重）之间：
 * qps=200 / capacity=1000（容量吸收整场瞬时上报尖峰，200/s 平滑整流脚本刷写）。
 */
class ExamTakingControllerRateLimitGuardTest {

    private RateLimit rateLimitOf(String methodName, Class<?>... paramTypes) {
        Method method = assertDoesNotThrowFind(methodName, paramTypes);
        RateLimit rateLimit = method.getAnnotation(RateLimit.class);
        assertNotNull(rateLimit, "写端点 " + methodName + " 必须声明 @RateLimit（裸写端点＝可无限刷写）");
        return rateLimit;
    }

    private Method assertDoesNotThrowFind(String methodName, Class<?>... paramTypes) {
        try {
            return ExamTakingController.class.getDeclaredMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("控制器方法签名已变更，本护栏需同步：" + methodName, e);
        }
    }

    private Class<?>[] behaviorReportParams() {
        return new Class<?>[] {Long.class, com.exam.taking.dto.BehaviorReportRequest.class};
    }

    @Test
    @DisplayName("行为上报端点：qps=200 / capacity=1000 / key=behavior-report")
    void behaviorReportIsRateLimited() {
        RateLimit rateLimit = rateLimitOf("reportBehavior", behaviorReportParams());

        assertEquals(200.0, rateLimit.qps(), 1e-9,
                "上报为轻处理常态流量：200/s 整流脚本刷写，正常千人大考峰值全放行");
        assertEquals(1000, rateLimit.capacity(),
                "桶容量吸收整场瞬时上报尖峰（千人大考偶发切屏/失焦/粘贴）");
        assertEquals("behavior-report", rateLimit.key(),
                "独立 key：与其他端点各自的令牌桶隔离，避免互相借令牌");
    }

    @Test
    @DisplayName("量级论证：上报 qps 介于开考拉卷(2000)与最重的交卷(500)之间")
    void behaviorReportQpsSitsBetweenPullPaperAndSubmit() {
        double pullPaper = rateLimitOf("enter", Long.class, jakarta.servlet.http.HttpServletRequest.class).qps();
        double behavior = rateLimitOf("reportBehavior", behaviorReportParams()).qps();
        double submit = rateLimitOf("submit", Long.class, com.exam.taking.dto.SubmitRequest.class).qps();

        assertTrue(behavior < pullPaper,
                "上报不得越过开考瞬时拉卷限流：" + behavior + " < " + pullPaper);
        assertTrue(behavior < submit,
                "上报比交卷轻且量级更小（交卷单次走三重幂等 + MQ），阈值应更保守："
                        + behavior + " < " + submit);
    }

    @Test
    @DisplayName("重写端点全部受限：此前唯一的裸@PostMapping 写端点（reportBehavior）已补注解")
    void noRemainingUnprotectedPostEndpoint() {
        // 仅 @PostMapping（重写端点）纳入断言：@PutMapping 的 saveDraft 走 30s 周期自动保存，
        // 本变更未纳入其限流范围——不为凑"清零"去改范围外的端点（白名单外语义不动）。
        long postEndpoints = Arrays.stream(ExamTakingController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PostMapping.class))
                .count();
        assertTrue(postEndpoints >= 3, "前置：本控制器 @PostMapping 写端点应≥3（enter/behavior/submit），实际=" + postEndpoints);

        String naked = Arrays.stream(ExamTakingController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PostMapping.class))
                .filter(m -> !m.isAnnotationPresent(RateLimit.class))
                .map(Method::getName)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        assertTrue(naked.isEmpty(), "@PostMapping 写端点必须全部受限流保护，裸写端点=" + naked);
    }
}
