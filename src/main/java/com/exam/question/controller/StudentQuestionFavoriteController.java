package com.exam.question.controller;

import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import com.exam.question.dto.StudentQuestionFavoritePageResponse;
import com.exam.question.service.StudentQuestionFavoriteService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生题目个人收藏夹接口（add-student-question-favorites）。
 */
@RestController
@RequestMapping("/api/student/favorites")
@RequirePermission("exam:take")
public class StudentQuestionFavoriteController {

    private final StudentQuestionFavoriteService favoriteService;

    public StudentQuestionFavoriteController(StudentQuestionFavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    /** 收藏题目：幂等写入，若题目不存在或已删除抛 404 */
    @PostMapping("/{questionId}")
    public ApiResponse<Void> favorite(@PathVariable Long questionId) {
        favoriteService.favorite(questionId);
        return ApiResponse.success();
    }

    /** 取消收藏题目：幂等移除 */
    @DeleteMapping("/{questionId}")
    public ApiResponse<Void> unfavorite(@PathVariable Long questionId) {
        favoriteService.unfavorite(questionId);
        return ApiResponse.success();
    }

    /** 分页查询本人收藏题目列表 */
    @GetMapping
    public ApiResponse<StudentQuestionFavoritePageResponse> listFavorites(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer type,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(favoriteService.listFavorites(page, size, type, keyword));
    }

    /** 批量检测题目收藏状态 */
    @GetMapping("/check")
    public ApiResponse<List<Long>> checkFavorites(
            @RequestParam(required = false) List<Long> questionIds) {
        return ApiResponse.success(favoriteService.checkFavorites(questionIds));
    }
}
