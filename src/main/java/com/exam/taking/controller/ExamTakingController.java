package com.exam.taking.controller;

import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import com.exam.taking.dto.AutoSaveRequest;
import com.exam.taking.dto.AutoSaveResponse;
import com.exam.taking.dto.BehaviorReportRequest;
import com.exam.taking.dto.EnterExamResponse;
import com.exam.taking.dto.ExamListItem;
import com.exam.taking.dto.SubmitRequest;
import com.exam.taking.dto.SubmitResponse;
import com.exam.taking.service.ExamSubmitService;
import com.exam.taking.service.ExamTakingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生答题端接口（add-exam-taking）：
 * <ul>
 *   <li>考试列表——按 待考/进行中/已完成 分组；</li>
 *   <li>进入考试——首次生成个人快照（题序/选项乱序锁定）+ 启动个人倒计时；
 *       重复进入/刷新返回同一快照与草稿（不换题、断线恢复）；</li>
 *   <li>答题数据——题目不含答案，倒计时以服务端时间为准（前端仅展示）；</li>
 *   <li>自动保存——30s 周期推送 Redis 草稿（版本合并，多端冲突以最新为准）；</li>
 *   <li>行为上报——切屏/失焦事件只记录不强制交卷；</li>
 *   <li>交卷——手动/前端归零共用入口，三重幂等 + MQ 削峰，重复提交返回首次结果。</li>
 * </ul>
 * 类级 {@code @RequirePermission("exam:take")}（权限点已预置：STUDENT/ADMIN 均有）。
 */
@RestController
@RequestMapping("/api/exam-taking")
@RequirePermission("exam:take")
public class ExamTakingController {

    private final ExamTakingService takingService;
    private final ExamSubmitService submitService;

    public ExamTakingController(ExamTakingService takingService, ExamSubmitService submitService) {
        this.takingService = takingService;
        this.submitService = submitService;
    }

    /** 我的考试列表：待考 / 进行中（可续答）/ 已完成 三组 */
    @GetMapping("/exams")
    public ApiResponse<List<ExamListItem>> myExams() {
        return ApiResponse.success(takingService.myExams());
    }

    /** 进入考试并开始：首次创建答卷（个人快照 + 开始计时），重复进入幂等返回同一快照 */
    @PostMapping("/exams/{examId}/enter")
    public ApiResponse<EnterExamResponse> enter(@PathVariable Long examId) {
        return ApiResponse.success(takingService.enter(examId));
    }

    /** 答题数据：个人快照题目（不含答案）+ 草稿 + 服务端剩余时间（断线恢复入口） */
    @GetMapping("/exams/{examId}/paper")
    public ApiResponse<EnterExamResponse> paper(@PathVariable Long examId) {
        return ApiResponse.success(takingService.current(examId));
    }

    /** 自动保存（前端 30s 周期调用）：答案 + 标记题落 Redis 草稿，返回受理结果与最新版本 */
    @PutMapping("/exams/{examId}/draft")
    public ApiResponse<AutoSaveResponse> saveDraft(@PathVariable Long examId,
                                                   @Valid @RequestBody AutoSaveRequest request) {
        return ApiResponse.success(takingService.saveDraft(examId, request));
    }

    /** 切屏/失焦行为上报：落行为日志，只记录不强制交卷 */
    @PostMapping("/exams/{examId}/behavior")
    public ApiResponse<Void> reportBehavior(@PathVariable Long examId,
                                            @Valid @RequestBody BehaviorReportRequest request) {
        takingService.reportBehavior(examId, request);
        return ApiResponse.success();
    }

    /**
     * 交卷：手动交卷与前端倒计时归零强制提交共用（submitType 区分来源）。
     * 三重幂等（SETNX 锁 + 防重表 + 状态机 CAS）保证只提交一次；重复提交返回首次结果；
     * 答案经 MQ 削峰异步批量落库，本接口返回即代表交卷成功（落库最终一致）。
     */
    @PostMapping("/exams/{examId}/submit")
    public ApiResponse<SubmitResponse> submit(@PathVariable Long examId,
                                              @RequestBody(required = false) SubmitRequest request) {
        return ApiResponse.success(submitService.submit(examId, request));
    }
}
