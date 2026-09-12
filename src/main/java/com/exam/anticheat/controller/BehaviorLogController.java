package com.exam.anticheat.controller;

import com.exam.anticheat.dto.BehaviorLogPageResponse;
import com.exam.anticheat.dto.BehaviorTimelineResponse;
import com.exam.anticheat.service.BehaviorLogQueryService;
import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行为日志查询接口（教师端，add-anti-cheat「行为日志时间线」需求）：
 * <ul>
 *   <li>分页筛选——按考试 + 学生/事件类型/严重度组合过滤（均按 event_time 升序）；</li>
 *   <li>学生时间线——单个学生的完整行为轨迹 + 严重度分布（监考大屏点击学生即打开）。</li>
 * </ul>
 * 类级 {@code @RequirePermission("exam:manage")}：权限点仅 TEACHER/ADMIN 持有，
 * STUDENT 访问直接 403（spec「学生不可见」场景的垂直拦截）；
 * 教师间水平越权由 Service 层 owner 校验兜底（只看自己创建的考试）。
 */
@RestController
@RequestMapping("/api/exams/{examId}/behavior-logs")
@RequirePermission("exam:manage")
public class BehaviorLogController {

    private final BehaviorLogQueryService queryService;

    public BehaviorLogController(BehaviorLogQueryService queryService) {
        this.queryService = queryService;
    }

    /** 行为日志分页：可按学生/事件类型/严重度筛选，时间升序展示行为轨迹 */
    @GetMapping
    public ApiResponse<BehaviorLogPageResponse> page(@PathVariable Long examId,
                                                     @RequestParam(required = false) Long studentId,
                                                     @RequestParam(required = false) String eventType,
                                                     @RequestParam(required = false) Integer severity,
                                                     @RequestParam(defaultValue = "1") long page,
                                                     @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.success(queryService.page(examId, studentId, eventType, severity, page, size));
    }

    /** 单个学生的行为时间线：完整轨迹 + 低/中/高严重度统计 */
    @GetMapping("/timeline")
    public ApiResponse<BehaviorTimelineResponse> timeline(@PathVariable Long examId,
                                                          @RequestParam Long studentId) {
        return ApiResponse.success(queryService.timeline(examId, studentId));
    }
}
