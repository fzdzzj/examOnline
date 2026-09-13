package com.exam.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.exam.common.slow_sql.SlowSqlInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置：created_time / updated_time 自动填充 + 分页插件。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createdTime", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updatedTime", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedTime", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }

    /**
     * 分页插件（add-question-bank 引入）：题目/试卷分页查询依赖它生成 LIMIT 子句，
     * 缺失时 selectPage 会退化为全表查询。方言按主库 MySQL 配置，H2 兼容模式同样接受 LIMIT 语法。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 注册慢 SQL 拦截器（add-performance-deepening 阶段8 可观测性 task6，只加注册行）：
     * 作为 MyBatis {@code Interceptor} Bean 声明后，MyBatis-Plus 自动装配会把本 Bean
     * 加入 SqlSessionFactory 的拦截器链，与分页插件互不干扰。阈值经构造注入（application.yml）。
     */
    @Bean
    public SlowSqlInterceptor slowSqlInterceptor(
            @Value("${exam.monitor.slow-sql-threshold-ms:1000}") long thresholdMs) {
        return new SlowSqlInterceptor(thresholdMs);
    }
}
