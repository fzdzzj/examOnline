package com.exam.adaptive.controller;

import com.exam.adaptive.dto.AdaptiveQuizAnswerRequest;
import com.exam.adaptive.dto.AdaptiveQuizAnswerResponse;
import com.exam.adaptive.dto.AdaptiveQuizReportResponse;
import com.exam.adaptive.dto.AdaptiveQuizStartRequest;
import com.exam.adaptive.dto.AdaptiveQuizStartResponse;
import com.exam.adaptive.service.IrtCatService;
import com.exam.auth.security.RequirePermission;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.ApiResponse;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生 CAT 自适应测验接口（spec add-irt-adaptive-testing）——三端点：
 * <ul>
 *   <li>{@code POST /api/scores/my/adaptive-quiz/start}：开局，返回 b 最接近 0 的首题（不泄露答案解析）；</li>
 *   <li>{@code POST /api/scores/my/adaptive-quiz/{sessionId}/answer}：单题作答，即时 θ 更新 + 下一题/终局信号；</li>
 *   <li>{@code GET /api/scores/my/adaptive-quiz/{sessionId}/report}：能力报告（θ/SE/正确率/终止原因/逐题轨迹）。</li>
 * </ul>
 *
 * <p>归属隔离：三端点一律以 {@link SecurityUtil#getUserId()} 作为会话归属人，
 * 服务层对 sessionId 做归属校验（越权 403），不从请求体接受任何 studentId。
 */
@RestController
@RequirePermission("score:view")
public class AdaptiveQuizController {

    private final IrtCatService irtCatService;

    public AdaptiveQuizController(IrtCatService irtCatService) {
        this.irtCatService = irtCatService;
    }

    /** 开局（题数与 SE 阈值可配，缺省 10 题 / SE 0.30）。 */
    @PostMapping("/api/scores/my/adaptive-quiz/start")
    public ApiResponse<AdaptiveQuizStartResponse> startAdaptiveQuiz(
            @Valid @RequestBody(required = false) AdaptiveQuizStartRequest request) {
        return ApiResponse.success(irtCatService.start(currentStudentId(), request));
    }

    /** 提交单题作答：即时判定 + θ 更新 + 下一题或终局信号。 */
    @PostMapping("/api/scores/my/adaptive-quiz/{sessionId}/answer")
    public ApiResponse<AdaptiveQuizAnswerResponse> submitAdaptiveQuizAnswer(
            @PathVariable Long sessionId,
            @Valid @RequestBody AdaptiveQuizAnswerRequest request) {
        return ApiResponse.success(irtCatService.answer(currentStudentId(), sessionId, request));
    }

    /** 能力报告（仅会话归属人可查，越权按既有口径 403 拒绝）。 */
    @GetMapping("/api/scores/my/adaptive-quiz/{sessionId}/report")
    public ApiResponse<AdaptiveQuizReportResponse> getAdaptiveQuizReport(@PathVariable Long sessionId) {
        return ApiResponse.success(irtCatService.report(currentStudentId(), sessionId));
    }

    private Long currentStudentId() {
        Long studentId = SecurityUtil.getUserId();
        if (studentId == null) {
            throw new BusinessException(ResponseCode.TOKEN_INVALID);
        }
        return studentId;
    }
}
