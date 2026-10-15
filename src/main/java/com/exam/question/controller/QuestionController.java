package com.exam.question.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.question.dto.BatchDeleteQuestionsRequest;
import com.exam.question.dto.BatchDeleteQuestionsResponse;
import com.exam.question.dto.QuestionCreateRequest;
import com.exam.question.dto.QuestionDuplicateItemResponse;
import com.exam.question.dto.QuestionPageResponse;
import com.exam.question.dto.QuestionResponse;
import com.exam.question.dto.TagResponse;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionType;
import com.exam.question.entity.Tag;
import com.exam.question.service.QuestionDuplicateService;
import com.exam.question.service.QuestionService;
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
import java.util.Map;

/**
 * 题目接口（教师题库）：单选/多选/判断/简答的 CRUD 与软删除、分页筛选。
 * 类级 {@code @RequirePermission("question:manage")} 挡住学生（RBAC 权限点）；
 * 水平越权（教师间互访）由 Service 层 owner 校验兜底。
 */
@RestController
@RequestMapping("/api/questions")
@RequirePermission("question:manage")
public class QuestionController {

    private final QuestionService questionService;
    private final QuestionDuplicateService questionDuplicateService;

    public QuestionController(QuestionService questionService,
                              QuestionDuplicateService questionDuplicateService) {
        this.questionService = questionService;
        this.questionDuplicateService = questionDuplicateService;
    }

    /** 创建题目（答案入库前归一化） */
    @PostMapping
    public ApiResponse<QuestionResponse> create(@Valid @RequestBody QuestionCreateRequest request) {
        return ApiResponse.success(toResponse(questionService.create(request), List.of()));
    }

    /**
     * 分页查询题目：page 从 1 起；
     * 筛选参数均可选——type（1单选 2多选 3判断 4简答 5填空）、difficulty（1易 2中 3难）、tagId、keyword（题干模糊）。
     */
    @GetMapping
    public ApiResponse<QuestionPageResponse> page(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "10") long size,
                                                  @RequestParam(required = false) Integer type,
                                                  @RequestParam(required = false) Integer difficulty,
                                                  @RequestParam(required = false) Long tagId,
                                                  @RequestParam(required = false) String keyword) {
        Page<Question> result = questionService.page(page, size, type, difficulty, tagId, keyword);
        // 批量装配标签，避免逐题查询
        List<Long> questionIds = result.getRecords().stream().map(Question::getId).toList();
        Map<Long, List<Tag>> tagMap = questionService.loadTagsByQuestionIds(questionIds);
        List<QuestionResponse> list = result.getRecords().stream()
                .map(question -> toResponse(question, tagMap.getOrDefault(question.getId(), List.of())))
                .toList();
        return ApiResponse.success(new QuestionPageResponse(
                list, result.getTotal(), result.getCurrent(), result.getSize()));
    }

    /** 题目详情（含标签） */
    @GetMapping("/{id}")
    public ApiResponse<QuestionResponse> detail(@PathVariable Long id) {
        Question question = questionService.getOwnedQuestion(id);
        Map<Long, List<Tag>> tagMap = questionService.loadTagsByQuestionIds(List.of(id));
        return ApiResponse.success(toResponse(question, tagMap.getOrDefault(id, List.of())));
    }

    /** 更新题目（全量覆盖；tagIds=null 表示保留原关联） */
    @PutMapping("/{id}")
    public ApiResponse<QuestionResponse> update(@PathVariable Long id,
                                                @Valid @RequestBody QuestionCreateRequest request) {
        Question question = questionService.update(id, request);
        Map<Long, List<Tag>> tagMap = questionService.loadTagsByQuestionIds(List.of(id));
        return ApiResponse.success(toResponse(question, tagMap.getOrDefault(id, List.of())));
    }

    /** 软删除题目（打 is_deleted 标记，不再参与后续组卷） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        questionService.softDelete(id);
        return ApiResponse.success();
    }

    /**
     * 批量删除题目（逐题结果信封）：部分成功如实返回 succeeded/failed，不把部分成功
     * 伪装成全部成功；单题端点 {@link #delete} 原样保留，语义不变。
     */
    @PostMapping("/batch-delete")
    public ApiResponse<BatchDeleteQuestionsResponse> batchDelete(
            @Valid @RequestBody BatchDeleteQuestionsRequest request) {
        return ApiResponse.success(questionService.batchDelete(request.getIds()));
    }

    /**
     * 题目查重预检（add-question-duplicate-detection，知情防呆）：
     * 检索归属当前教师题库（ADMIN 查全量）中与题干相似度 ≥ threshold 的前 limit 条相似题目，
     * 按相似度降序。stem 必填且去除空白后不少于 2 个字符，否则 400；
     * excludeId 排除编辑场景下的题目自身。
     *
     * @param stem      待检测题干
     * @param excludeId 编辑题目时排除自身 id（可选）
     * @param type      题型过滤（可选）
     * @param threshold 相似度阈值，默认 0.6
     * @param limit     返回条数，默认 5、上限 10
     */
    @GetMapping("/check-duplicate")
    public ApiResponse<List<QuestionDuplicateItemResponse>> checkDuplicate(
            @RequestParam(required = false) String stem,
            @RequestParam(required = false) Long excludeId,
            @RequestParam(required = false) Integer type,
            @RequestParam(required = false) Double threshold,
            @RequestParam(required = false) Integer limit) {
        if (stem == null || stem.trim().length() < 2) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "题干不能为空且有效字符不少于 2 个");
        }
        return ApiResponse.success(questionDuplicateService.checkDuplicate(
                stem, excludeId, type, threshold, limit));
    }

    private QuestionResponse toResponse(Question question, List<Tag> tags) {
        List<TagResponse> tagResponses = tags.stream()
                .map(tag -> new TagResponse(tag.getId(), tag.getName(), tag.getType()))
                .toList();
        return new QuestionResponse(
                question.getId(),
                question.getType(),
                QuestionType.of(question.getType()) == null ? null
                        : QuestionType.of(question.getType()).getLabel(),
                question.getContent(),
                question.getImageUrl(),
                questionService.parseChoices(question.getChoices()),
                question.getCorrectAnswer(),
                question.getScore(),
                question.getDifficulty(),
                question.getAnalysis(),
                tagResponses,
                question.getCreatedBy(),
                question.getCreatedTime(),
                question.getUpdatedTime());
    }
}
