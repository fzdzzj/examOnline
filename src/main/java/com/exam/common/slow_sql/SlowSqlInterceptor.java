package com.exam.common.slow_sql;

import com.exam.common.RequestIdFilter;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;

import java.sql.Statement;
import java.util.Properties;

/**
 * MyBatis 慢 SQL 拦截器（add-performance-deepening 阶段8 可观测性 task6）：
 * 拦截 StatementHandler 的 query/update，环绕计时，单条 SQL 执行超过阈值（默认 1000ms）
 * 输出 WARN 日志，日志含执行耗时、SQL 与原样透传的 requestId。
 *
 * <p><b>为什么用 MyBatis Interceptor 而非 AOP：</b>
 * <ol>
 *   <li><b>能拿到真实 SQL 与参数：</b>放在业务 Service 的 AOP 只能拿到方法签名，拿不到 MyBatis
 *       实际执行的 SQL 文本和绑定参数；而拦截器身处 ORM 执行现场，可从 {@link StatementHandler} 的
 *       {@link BoundSql} 直接取到带占位符的 SQL——慢 SQL 定位最需要的就是这句真实语句；</li>
 *   <li><b>覆盖全部真假执行为：</b>AOP 包在 Service 方法外，会把缓存命中/内存处理都算进耗时；
 *       拦截器只包"真正触达 DB 的语句"，耗时口径与 DBA 关心的执行时间一致，无虚高误报；</li>
 *   <li><b>拦截点干净、零侵入业务：</b>继承 MyBatis 既有扩展点（同分页插件同构），加一行注册即全局生效，
 *       无需给每个 Service 方法打注解，新 SQL 自动纳入监控。</li>
 * </ol>
 *
 * <p><b>慢 SQL 与 requestId 如何关联：</b>本拦截器线程内读 {@code MDC[requestId]}——该值由
 * {@link RequestIdFilter} 在每个 HTTP 请求进入时写入。同一请求全链路的日志（HTTP 入口、慢 SQL、
 * 业务日志）都带这个 ID，因此按 requestId 即可把「某次慢 SQL」关联回「发起该请求的学生/接口」，
 * 与 {@code BusinessMetrics} 的聚合指标（定方向）+ 本日志（定个案）组成排查闭环。
 *
 * @see com.exam.monitoring.metrics.BusinessMetrics
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "query",
                args = {Statement.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update",
                args = {Statement.class})
})
public class SlowSqlInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlInterceptor.class);

    /** 慢 SQL 阈值（毫秒）：超过即输出 WARN，可在 application.yml exam.monitor.slow-sql-threshold-ms 调整 */
    private final long thresholdMs;

    public SlowSqlInterceptor(@Value("${exam.monitor.slow-sql-threshold-ms:1000}") long thresholdMs) {
        this.thresholdMs = thresholdMs;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            return invocation.proceed();
        } finally {
            long cost = System.currentTimeMillis() - start;
            // 超阈值才打 WARN：避免每句 SQL 都记日志造成海量噪音
            if (cost >= thresholdMs) {
                String sql = extractSql(invocation);
                String requestId = MDC.get(RequestIdFilter.MDC_KEY);
                log.warn("慢 SQL 超过阈值 {}ms，实际 {}ms，requestId={}，SQL: {}", thresholdMs, cost, requestId, sql);
            }
        }
    }

    /** 取被拦截语句的真实 SQL：多行/换行压缩成单行便于落日志，参数不符不阻塞主链路。 */
    private String extractSql(Invocation invocation) {
        try {
            StatementHandler handler = (StatementHandler) invocation.getTarget();
            BoundSql boundSql = handler.getBoundSql();
            return boundSql.getSql().replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // 无外部属性配置，阈值经构造注入（application.yml），无需从 properties 读取
    }
}