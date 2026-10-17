package com.exam.exam.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 Spring @Scheduled 定时任务（add-exam-management）：
 * 考试状态机扫表（定时发布/自然结束）依赖它驱动，
 * 不引入 Quartz/xxl-job 等外部调度中间件（见提案风险缓解决策）。
 *
 * <p>调度线程池容量走 {@code spring.task.scheduling.pool.size}（harden-scheduler-pool-config，
 * B.2 中危 M2，base application.yml 声明缺省 2、env 可覆盖）：Spring 默认单线程会让
 * sweep 单轮的 MQ confirm 等待在 broker 故障时阻塞状态机流转与开考提醒的定时触发；
 * fixed-delay 按任务自互斥，同任务不因池扩而自我重叠，多实例幂等安全性不受影响。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
