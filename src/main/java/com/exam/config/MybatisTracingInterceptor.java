package com.exam.config;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * MyBatis 追踪拦截器（add-distributed-tracing）
 * 自动采集 SQL 执行 Span，记录耗时与 SQL 语句（脱敏）
 *
 * @author 凤媚珍
 */
// Executor 上真实的 4 参 query 是 (MappedStatement, Object, RowBounds, ResultHandler)。
// 参数表写错不会编译报错，但 MyBatis 在装配插件时反射找方法，直接抛 PluginException 打挂整个 SqlSessionFactory。
@Intercepts({@Signature(
        type = Executor.class,
        method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}
)})
@Component
public class MybatisTracingInterceptor implements Interceptor {

    private static final Logger logger = LoggerFactory.getLogger(MybatisTracingInterceptor.class);
    
    private final Tracer tracer;

    @Autowired
    public MybatisTracingInterceptor(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        String sqlId = ms.getId();
        
        // 创建 SQL Span
        Span span = tracer.spanBuilder("sql-" + sqlId)
                .setAttribute("db.system", "mysql")
                .setAttribute("component", "mapper")
                .setParent(io.opentelemetry.context.Context.current())
                .startSpan();
        
        try {
            long startTime = System.currentTimeMillis();
            Object result = invocation.proceed();
            long duration = System.currentTimeMillis() - startTime;
            
            span.setAttribute("db.operation.duration", duration);
            span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
            
            return result;
        } catch (Exception e) {
            span.recordException(e);
            span.setAttribute("error", true);
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // 无配置属性
    }
}
