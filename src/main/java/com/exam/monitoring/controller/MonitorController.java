package com.exam.monitoring.controller;

import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import com.exam.monitoring.dto.MonitorOverviewResponse;
import com.exam.monitoring.service.MonitorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 监考大屏接口（add-anti-cheat「监考大屏」需求）：
 * 教师端轮询 GET overview 刷新——在线/离线/已交卷人数、逐学生进度、异常高亮
 * （异常学生点击后跳 GET /api/exams/{examId}/behavior-logs/timeline 查看行为轨迹）。
 *
 * <p>类级 {@code @RequirePermission("exam:manage")}：STUDENT 访问 403；
 * 教师间水平越权由 Service 层 owner 校验兜底。
 */
@RestController
@RequestMapping("/api/exams/{examId}/monitor")
@RequirePermission("exam:manage")
public class MonitorController {

    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /** 监考大屏总览：实时人数统计 + 答题进度 + 异常高亮（前端轮询，建议 10-30s 间隔） */
    @GetMapping("/overview")
    public ApiResponse<MonitorOverviewResponse> overview(@PathVariable Long examId) {
        return ApiResponse.success(monitorService.overview(examId));
    }
}
