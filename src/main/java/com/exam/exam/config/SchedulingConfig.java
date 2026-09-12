package com.exam.exam.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 Spring @Scheduled 定时任务（add-exam-management）：
 * 考试状态机扫表（定时发布/自然结束）依赖它驱动，
 * 不引入 Quartz/xxl-job 等外部调度中间件（见提案风险缓解决策）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
