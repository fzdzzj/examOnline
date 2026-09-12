package com.exam.question.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import com.exam.question.dto.QuestionCreateRequest;
import com.exam.question.dto.QuestionPageResponse;
import com.exam.question.dto.QuestionResponse;
import com.exam.question.dto.TagResponse;
import com.exam.question.entity.Question;
import com.exam.question.entity.QuestionType;
import com.exam.question.entity.Tag;
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

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    /** 创建题目（答案入库前归一化） */
    @PostMapping
    public ApiResponse<QuestionResponse> create(@Valid @RequestBody QuestionCreateRequest request) {
        return ApiResponse.success(toResponse(questionService.create(request), List.of()));
    }

    /**
     * 分页查询题目：page 从 1 起；
     * 筛选参数均可选——type（1单选 2多选 3判断 4简答）、difficulty（1易 2中 3难）、tagId、keyword（题干模糊）。
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
