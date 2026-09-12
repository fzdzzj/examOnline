package com.exam.exam.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import com.exam.exam.dto.ExamCreateRequest;
import com.exam.exam.dto.ExamDetailResponse;
import com.exam.exam.dto.ExamResponse;
import com.exam.exam.dto.ExamSnapshotResponse;
import com.exam.exam.dto.ExamUpdateRequest;
import com.exam.exam.entity.Exam;
import com.exam.exam.service.ExamService;
import com.exam.exam.service.ExamSnapshotService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 考试管理接口：
 * <ul>
 *   <li>考试 CRUD——绑定试卷/课程班级、时间窗与个人时长（exams）；</li>
 *   <li>状态生命周期——发布（生成快照）、定时开考/自然结束（定时任务推进）、教师提前结束；</li>
 *   <li>考试快照读取——答题/判分/回看的统一数据源。</li>
 * </ul>
 * 类级 {@code @RequirePermission("exam:manage")}（权限点已预置：TEACHER/ADMIN 均有）；
 * 教师间水平越权由 Service 层 owner 校验兜底。
 */
@RestController
@RequestMapping("/api/exams")
@RequirePermission("exam:manage")
public class ExamController {

    private final ExamService examService;
    private final ExamSnapshotService examSnapshotService;

    public ExamController(ExamService examService, ExamSnapshotService examSnapshotService) {
        this.examService = examService;
        this.examSnapshotService = examSnapshotService;
    }

    /** 创建考试：绑定试卷/课程班级，设定时间窗与个人时长；初始状态未开始 */
    @PostMapping
    public ApiResponse<ExamResponse> create(@Valid @RequestBody ExamCreateRequest request) {
        return ApiResponse.success(toResponse(examService.create(request)));
    }

    /** 考试分页（教师仅见自己的考试） */
    @GetMapping
    public ApiResponse<List<ExamResponse>> page(@RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "10") long size) {
        Page<Exam> result = examService.page(page, size);
        return ApiResponse.success(result.getRecords().stream().map(this::toResponse).toList());
    }

    /** 考试详情（含绑定试卷标题与防作弊配置） */
    @GetMapping("/{id}")
    public ApiResponse<ExamDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(examService.detail(id));
    }

    /** 更新考试（部分更新）：仅未发布且未开始的考试允许修改 */
    @PutMapping("/{id}")
    public ApiResponse<ExamDetailResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody ExamUpdateRequest request) {
        return ApiResponse.success(examService.update(id, request));
    }

    /** 删除考试（软删）：仅未发布且未开始的考试可删，历史考试与快照保留 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        examService.delete(id);
        return ApiResponse.success();
    }

    /** 教师提前结束：进行中 → 已结束并留强制交卷标记；状态迁移经乐观锁 CAS，并发仅一次成功 */
    @PostMapping("/{id}/force-end")
    public ApiResponse<ExamDetailResponse> forceEnd(@PathVariable Long id) {
        return ApiResponse.success(examService.forceEnd(id));
    }

    /** 发布考试：学生可见、生成考试快照（唯一时机），状态仍为未开始等待定时开考 */
    @PostMapping("/{id}/publish")
    public ApiResponse<ExamDetailResponse> publish(@PathVariable Long id) {
        return ApiResponse.success(examService.publish(id));
    }

    /** 读取考试快照：后续答题/判分/回看统一以此为准，试卷或题目再修改均不影响快照内容 */
    @GetMapping("/{id}/snapshot")
    public ApiResponse<ExamSnapshotResponse> getSnapshot(@PathVariable Long id) {
        return ApiResponse.success(examSnapshotService.getCurrent(id));
    }

    private ExamResponse toResponse(Exam exam) {
        return new ExamResponse(exam.getId(), exam.getTitle(), exam.getPaperId(),
                exam.getCourseId(), exam.getClassId(),
                exam.getStartTime(), exam.getEndTime(), exam.getDurationMinutes(), exam.getAllowLateMinutes(),
                exam.getStatus(), exam.getPublished(), exam.getForceEnd(),
                exam.getSnapshotId(), exam.getCreatedBy(), exam.getCreatedTime());
    }
}
