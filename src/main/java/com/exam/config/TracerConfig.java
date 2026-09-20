package com.exam.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenTelemetry 追踪配置（add-distributed-tracing）。
 *
 * <p>SDK、Span 导出器与采样率全部由 opentelemetry-spring-boot-starter 按 {@code otel.*} 属性装配
 * （见 application.yml），本类只把业务代码要用的 {@link Tracer} 暴露成 Bean。
 *
 * <p>这里原本自建过一个 {@code OpenTelemetry} Bean，而它正是把整个应用打挂的原因：自建 Bean 让
 * starter 的 SDK 自动装配整块退避，但 starter 的 OTLP 导出器自动装配仍要注入只有那块才提供的
 * ConfigProperties，结果 20 个 {@code @SpringBootTest} 全红、应用也无法启动。SDK 只能有一个装配方。
 *
 * @author 凤媚珍
 */
@Configuration
public class TracerConfig {

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("exam-online");
    }
}
