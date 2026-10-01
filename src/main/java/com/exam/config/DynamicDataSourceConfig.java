package com.exam.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多数据源（读写分离）基础配置（add-performance-deepening task3+4）：
 * 数据源本身（master/slave 双连接池、primary=master、@DS 切面）由 dynamic-datasource 自动配置，
 * 本类只负责装配读己之写基础设施：
 * <ul>
 *   <li>{@link ReadYourWriteMark}：线程级写后读己之写窗口标记（写 Service 注入并打点）；</li>
 *   <li>{@link ReadYourWriteRouter}：窗口内把 @DS("slave") 读强制转主库的切面。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class DynamicDataSourceConfig {

    /** 读己之写窗口标记：写操作完成后调用 mark() 打点。 */
    @Bean
    public ReadYourWriteMark readYourWriteMark(
            @Value("${exam.datasource.read-your-write-window-ms:5000}") long windowMillis) {
        return new ReadYourWriteMark(windowMillis);
    }

    /** 读己之写路由切面：依赖标记组件决定是否强制转主库。 */
    @Bean
    public ReadYourWriteRouter readYourWriteRouter(ReadYourWriteMark mark) {
        return new ReadYourWriteRouter(mark);
    }
}