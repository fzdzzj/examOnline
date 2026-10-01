package com.exam.submission.controller;

import com.exam.auth.security.RequireRole;
import com.exam.auth.security.RoleHierarchy;
import com.exam.common.ApiResponse;
import com.exam.submission.service.DlqReplayService;
import com.exam.submission.service.DlqReplayService.ReplayReport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 交卷死信管理端点（ADMIN）：有界重投。
 *
 * <p>死信稳态深度应为 0，持续非 0 必须查因而非清空。
 * 不引入常驻 DLQ 消费者：常驻消费要么 requeue 死循环，要么超限丢弃，复杂度远超收益；
 * 管理端点触发的、有界的、带留档的一次性重投才是正确粒度。
 */
@RestController
@RequestMapping("/api/admin/mq/dlq")
@RequireRole(RoleHierarchy.ADMIN)
public class DlqAdminController {

    private final DlqReplayService dlqReplayService;

    public DlqAdminController(DlqReplayService dlqReplayService) {
        this.dlqReplayService = dlqReplayService;
    }

    /**
     * 有界重投死信。{@code max} 会被 clamp 到 {@code exam.mq.dlq.replay-max}（默认 100），
     * 不允许一次抽干整个死信队列。
     */
    @PostMapping("/replay")
    public ApiResponse<ReplayReport> replay(@RequestParam(name = "max", defaultValue = "1") int max) {
        return ApiResponse.success(dlqReplayService.replayOnce(max));
    }
}
