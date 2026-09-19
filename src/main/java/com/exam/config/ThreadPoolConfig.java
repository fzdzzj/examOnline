package com.exam.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池隔离配置（spec add-thread-pool-isolation）：
 * 按业务域创建独立线程池，避免资源抢占风险。
 *
 * <p>三个独立线程池：
 * <ol>
 *   <li><b>submit</b>: 考试提交核心路径（core=10, max=50, queue=100）- 高优先级低延迟</li>
 *   <li><b>grade</b>: 评分与批量任务（core=20, max=100, queue=500）- 高吞吐批处理</li>
 *   <li><b>monitor</b>: 监控采集后台任务（core=5, max=20, queue=50）- 低优先级后台</li>
 * </ol>
 *
 * <p>拒绝策略：AbortPolicy + Micrometer 计数器告警
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "thread-pool.isolation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ThreadPoolConfig {

    // ==================== Submit 线程池（考试提交核心路径） ====================
    
    @Bean("submitExecutor")
    public ThreadPoolTaskExecutor submitExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心配置：高优先级低延迟场景
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        
        // 线程命名：便于监控和调试
        executor.setThreadNamePrefix("exam-submit-");
        executor.setKeepAliveSeconds(60);
        
        // 队列满时直接拒绝（不等待），触发告警
        RejectedExecutionHandler handler = (r, exec) -> {
            log.warn("Submit 线程池拒绝任务：queue_size={}, active_count={}, pool_size={}",
                    exec.getQueue().size(), exec.getActiveCount(), exec.getPoolSize());
            throw new RuntimeException("线程池已满，拒绝任务");
        };
        executor.setRejectedExecutionHandler(new MeteredRejectHandler(handler, "submit", meterRegistry));
        
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        
        executor.initialize();
        log.info("Submit 线程池初始化完成：core=10, max=50, queue=100");
        return executor;
    }

    // ==================== Grade 线程池（评分与批量任务） ====================
    
    @Bean("gradeExecutor")
    public ThreadPoolTaskExecutor gradeExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心配置：高吞吐批处理场景
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(100);
        executor.setQueueCapacity(500);
        
        // 线程命名
        executor.setThreadNamePrefix("exam-grade-");
        executor.setKeepAliveSeconds(60);
        
        // 队列满时拒绝并告警（CallerRunsHandler：由调用线程执行任务，提供背压）
        RejectedExecutionHandler handler = (r, exec) -> {
            log.warn("Grade 线程池队列已满，由调用线程执行任务：{}", Thread.currentThread().getName());
            r.run(); // 由当前线程执行，提供背压
        };
        executor.setRejectedExecutionHandler(new MeteredRejectHandler(handler, "grade", meterRegistry));
        
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        
        executor.initialize();
        log.info("Grade 线程池初始化完成：core=20, max=100, queue=500");
        return executor;
    }

    // ==================== Monitor 线程池（监控采集后台任务） ====================
    
    @Bean("monitorExecutor")
    public ThreadPoolTaskExecutor monitorExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心配置：低优先级后台采集
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(50);
        
        // 线程命名
        executor.setThreadNamePrefix("exam-monitor-");
        executor.setKeepAliveSeconds(30);
        
        // 队列满时拒绝并告警
        RejectedExecutionHandler handler = (r, exec) -> {
            log.warn("Monitor 线程池拒绝任务：queue_size={}, active_count={}, pool_size={}",
                    exec.getQueue().size(), exec.getActiveCount(), exec.getPoolSize());
            throw new RuntimeException("线程池已满，拒绝任务");
        };
        executor.setRejectedExecutionHandler(new MeteredRejectHandler(handler, "monitor", meterRegistry));
        
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        
        executor.initialize();
        log.info("Monitor 线程池初始化完成：core=5, max=20, queue=50");
        return executor;
    }

    /**
     * 带指标计数的拒绝处理器：拒绝时增加计数器，便于 Prometheus 监控告警
     */
    static class MeteredRejectHandler implements RejectedExecutionHandler {
        private final RejectedExecutionHandler delegate;
        private final Counter counter;
        private final AtomicInteger rejectedCount = new AtomicInteger(0);

        public MeteredRejectHandler(RejectedExecutionHandler delegate, String poolName, MeterRegistry meterRegistry) {
            this.delegate = delegate;
            this.counter = Counter.builder("thread_pool_rejected")
                    .tag("pool", poolName)
                    .description("Thread pool rejection count for " + poolName + " pool")
                    .register(meterRegistry);
            
            // 添加 Gauge 监控当前拒绝次数
            Gauge.builder("thread_pool_rejected_total", rejectedCount, AtomicInteger::get)
                    .tag("pool", poolName)
                    .register(meterRegistry);
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            rejectedCount.incrementAndGet();
            counter.increment();
            log.warn("线程池 {} 拒绝任务：queue_size={}, active_count={}, pool_size={}",
                    getPoolName(executor),
                    executor.getQueue().size(),
                    executor.getActiveCount(),
                    executor.getPoolSize());
            delegate.rejectedExecution(r, executor);
        }
        
        /**
         * 从 ThreadPoolExecutor 获取线程池名称
         */
        private String getPoolName(ThreadPoolExecutor executor) {
            try {
                java.util.concurrent.ThreadFactory factory = executor.getThreadFactory();
                if (factory != null && factory.getClass().getName().contains("Spring")) {
                    return "spring-thread-pool";
                }
                return "unknown";
            } catch (Exception e) {
                return "unknown";
            }
        }
    }
}
