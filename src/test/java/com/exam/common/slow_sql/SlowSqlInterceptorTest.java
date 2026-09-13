package com.exam.common.slow_sql;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 慢 SQL 拦截器单元测试（add-performance-deepening 阶段8 可观测性 task6）：
 * 不启动 Spring，直接 new 拦截器 + Plugin.wrap 造代理触发拦截，验证：
 * <ol>
 *   <li>执行超阈值 → 输出 WARN 日志（含耗时、SQL、requestId）；</li>
 *   <li>未超阈值 → 不输出 WARN（避免海量噪音），且方法结果正常透传；</li>
 *   <li>MDC 无 requestId（非 HTTP 上下文）→ 日志正常输出、requestId 为 null 不抛异常。</li>
 * </ol>
 */
class SlowSqlInterceptorTest {

    private final Logger slowSqlLogger = (Logger) LoggerFactory.getLogger(SlowSqlInterceptor.class);

    private ListAppender<ILoggingEvent> appender;

    private void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        slowSqlLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            slowSqlLogger.detachAppender(appender);
            appender = null;
        }
        MDC.clear();
    }

    private List<String> warnMessages() {
        return appender == null ? List.of() : appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * 造一个 StatementHandler 代理：getBoundSql() 返回一句真实 SQL，随后对其 query 触发拦截。
     * 仅 stub getBoundSql（SQL 来源），query 由 mock 默认返回，验证拦截器取句取参数并计时透传。
     */
    private void fireQuery(SlowSqlInterceptor interceptor, String sql) throws Throwable {
        StatementHandler handler = mock(StatementHandler.class, RETURNS_DEEP_STUBS);
        BoundSql boundSql = new BoundSql(new Configuration(), sql, new ArrayList<>(List.of()), null);
        when(handler.getBoundSql()).thenReturn(boundSql);

        StatementHandler wrapped = (StatementHandler) Plugin.wrap(handler, interceptor);
        wrapped.query((Statement) null, (ResultHandler) null);
        wrapped.update(null);
    }

    @Test
    @DisplayName("超过阈值：输出 WARN，日志含耗时、SQL 与 requestId")
    void warnsWhenOverThreshold() throws Throwable {
        attachAppender();
        MDC.put("requestId", "req-test-abc123");
        // 阈值 0ms：任何执行必然超阈值，确保触发 WARN 分支
        SlowSqlInterceptor interceptor = new SlowSqlInterceptor(0);
        String sql = "select * from exam where name = ? and id = ?";

        fireQuery(interceptor, sql);

        List<String> warns = warnMessages();
        assertTrue(warns.stream().anyMatch(m -> m.contains("慢 SQL")), "应输出慢 SQL WARN 日志");
        assertTrue(warns.stream().anyMatch(m -> m.contains(sql)), "WARN 日志应含真实 SQL 文本");
        assertTrue(warns.stream().anyMatch(m -> m.contains("req-test-abc123")), "WARN 日志应关联 requestId");
        assertTrue(warns.stream().anyMatch(m -> m.contains("ms")), "WARN 日志应含执行耗时");
    }

    @Test
    @DisplayName("未超阈值：不输出 WARN，避免每句 SQL 都写日志造成噪音")
    void silentWhenWithinThreshold() throws Throwable {
        attachAppender();
        MDC.put("requestId", "req-test-quick");
        // 阈值设到极大（约 292 年毫秒），实际执行绝不会超过 → 不触发 WARN
        SlowSqlInterceptor interceptor = new SlowSqlInterceptor(Long.MAX_VALUE);

        fireQuery(interceptor, "select 1");

        assertTrue(warnMessages().isEmpty(), "未超阈值不应输出慢 SQL 日志");
    }

    @Test
    @DisplayName("非 HTTP 上下文（MDC 无 requestId）：日志照常输出、requestId 为 null 不抛异常")
    void worksWithoutRequestId() throws Throwable {
        attachAppender();
        // 不 put requestId，模拟定时任务/后台线程发起 SQL
        SlowSqlInterceptor interceptor = new SlowSqlInterceptor(0);

        fireQuery(interceptor, "insert into log(msg) values (?)");

        assertTrue(warnMessages().stream().anyMatch(m -> m.contains("requestId=null")),
                "无 requestId 时应正常输出且 requestId 显示 null");
    }
}