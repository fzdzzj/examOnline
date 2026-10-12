package com.exam.exam.controller;

import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import com.exam.exam.service.ExamReservationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 考试预约接口（add-exam-reservation，学生端）：
 * <ul>
 *   <li>预约——仅已发布且未开始的预约制考试，且学生须属于考试绑定班级（资格与进入准入同源）；</li>
 *   <li>取消——仅开考前，物理删除（重约可重新插入，唯一索引幂等）。</li>
 * </ul>
 * 类级 {@code @RequirePermission("exam:take")}（权限点已预置：STUDENT/ADMIN 均有）。
 */
@RestController
@RequestMapping("/api/exam-reservations")
@RequirePermission("exam:take")
public class ReservationController {

    private final ExamReservationService reservationService;

    public ReservationController(ExamReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** 预约考试：已发布且未开始 + 同源资格校验；重复预约幂等成功。 */
    @PostMapping("/{examId}")
    public ApiResponse<Void> reserve(@PathVariable Long examId) {
        reservationService.reserve(examId);
        return ApiResponse.success();
    }

    /** 取消预约：仅开考前；开考后名单锁定不可取消。 */
    @DeleteMapping("/{examId}")
    public ApiResponse<Void> cancel(@PathVariable Long examId) {
        reservationService.cancel(examId);
        return ApiResponse.success();
    }
}