package com.exam.question.controller;

import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import com.exam.question.dto.TagCreateRequest;
import com.exam.question.dto.TagResponse;
import com.exam.question.entity.Tag;
import com.exam.question.service.TagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签接口：扁平四类（学科/难度/题型/自定义）的管理端点。
 * 题目与标签为多选关联，支持按标签筛选题目（见 /api/questions?tagId=）。
 */
@RestController
@RequestMapping("/api/tags")
@RequirePermission("question:manage")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    /** 创建标签（同类型下同名重复返回 1001） */
    @PostMapping
    public ApiResponse<TagResponse> create(@Valid @RequestBody TagCreateRequest request) {
        Tag tag = tagService.create(request);
        return ApiResponse.success(new TagResponse(tag.getId(), tag.getName(), tag.getType()));
    }

    /** 标签列表：type 可选（SUBJECT/DIFFICULTY/QUESTION_TYPE/CUSTOM） */
    @GetMapping
    public ApiResponse<List<TagResponse>> list(@RequestParam(required = false) String type) {
        return ApiResponse.success(tagService.list(type).stream()
                .map(tag -> new TagResponse(tag.getId(), tag.getName(), tag.getType()))
                .toList());
    }

    /** 删除标签（软删并清理题目关联） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        tagService.delete(id);
        return ApiResponse.success();
    }
}
