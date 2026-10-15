package com.exam.message.controller;

import com.exam.common.ApiResponse;
import com.exam.message.entity.UserMessage;
import com.exam.message.service.MessageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站内消息中心接口（add-exam-reservation）：
 * 本人消息列表（倒序）/ 未读数 / 单条标记已读。
 *
 * <p><b>鉴权仅要求登录，不特判角色</b>：类上没有 @RequirePermission/@RequireRole，
 * 由 WebMvcConfig 注册的 AuthenticationInterceptor（全部 /api/**）统一做登录鉴权——
 * 任意已登录用户（学生或教师）均可访问，消息按 user_id 隔离，不存在水平越权面。
 */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /** 本人消息列表（按时间倒序）。 */
    @GetMapping
    public ApiResponse<List<UserMessage>> list(
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(messageService.listMine(limit));
    }

    /** 本人未读数（消息铃铛角标）。 */
    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.success(messageService.unreadCount());
    }

    /** 单条标记已读（仅本人；未读数联动减少）。 */
    @PutMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        messageService.markRead(id);
        return ApiResponse.success();
    }

    /** 全部标为已读（仅本人：user_id 隔离；幂等，无未读也返回成功）。 */
    @PutMapping("/read-all")
    public ApiResponse<Integer> markAllRead() {
        return ApiResponse.success(messageService.markAllRead());
    }
}