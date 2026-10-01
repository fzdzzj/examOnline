package com.exam.clazz.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.auth.security.RequireRole;
import com.exam.auth.security.RoleHierarchy;
import com.exam.clazz.dto.ClassCreateRequest;
import com.exam.clazz.dto.ClassPageResponse;
import com.exam.clazz.dto.ClassResponse;
import com.exam.clazz.dto.ClassStudentItem;
import com.exam.clazz.dto.ClassUpdateRequest;
import com.exam.clazz.dto.JoinStudentRequest;
import com.exam.clazz.dto.TransferRequest;
import com.exam.clazz.entity.ClassEntity;
import com.exam.clazz.service.ClassService;
import com.exam.common.ApiResponse;
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
 * 班级管理接口（spec「班级管理」：班级 CRUD + 学生入班/移除/转班 + 班级学生列表）。
 * 类级 {@code @RequireRole(RoleHierarchy.TEACHER)}：ADMIN > TEACHER 可管理班级，学生 403；
 * 教师间水平越权由 Service 层 owner 校验兜底（复用 OwnershipGuard 模式）。
 */
@RestController
@RequestMapping("/api/classes")
@RequireRole(RoleHierarchy.TEACHER)
public class ClassController {

    private final ClassService classService;

    public ClassController(ClassService classService) {
        this.classService = classService;
    }

    /** 创建班级（归属当前教师） */
    @PostMapping
    public ApiResponse<ClassResponse> create(@Valid @RequestBody ClassCreateRequest request) {
        return ApiResponse.success(toResponse(classService.create(request)));
    }

    /** 班级分页（教师仅见自己归属的班级，ADMIN 可见全部） */
    @GetMapping
    public ApiResponse<ClassPageResponse> page(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "10") long size) {
        Page<ClassEntity> result = classService.page(page, size);
        List<ClassResponse> list = result.getRecords().stream().map(this::toResponse).toList();
        return ApiResponse.success(new ClassPageResponse(list, result.getTotal(),
                result.getCurrent(), result.getSize()));
    }

    /** 班级详情 */
    @GetMapping("/{id}")
    public ApiResponse<ClassResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(toResponse(classService.getOwnedClass(id)));
    }

    /** 更新班级（部分更新） */
    @PutMapping("/{id}")
    public ApiResponse<ClassResponse> update(@PathVariable Long id,
                                             @Valid @RequestBody ClassUpdateRequest request) {
        return ApiResponse.success(toResponse(classService.update(id, request)));
    }

    /** 删除班级（软删并清理入班关联） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        classService.delete(id);
        return ApiResponse.success();
    }

    /** 学生入班 */
    @PostMapping("/{id}/students")
    public ApiResponse<Void> joinStudent(@PathVariable Long id,
                                         @Valid @RequestBody JoinStudentRequest request) {
        classService.joinStudent(id, request.getUserId());
        return ApiResponse.success();
    }

    /** 学生移出班级 */
    @DeleteMapping("/{id}/students/{userId}")
    public ApiResponse<Void> removeStudent(@PathVariable Long id, @PathVariable Long userId) {
        classService.removeStudent(id, userId);
        return ApiResponse.success();
    }

    /** 学生转班（当前班级 → 目标班级）：仅更新 user_class.class_id，成绩随人（§12.6） */
    @PutMapping("/{id}/students/{userId}/transfer")
    public ApiResponse<Void> transfer(@PathVariable Long id, @PathVariable Long userId,
                                      @Valid @RequestBody TransferRequest request) {
        classService.transfer(id, userId, request.getTargetClassId());
        return ApiResponse.success();
    }

    /** 班级学生列表（该班当前全部学生，含学号/姓名/入班时间） */
    @GetMapping("/{id}/students")
    public ApiResponse<List<ClassStudentItem>> listStudents(@PathVariable Long id) {
        return ApiResponse.success(classService.listStudents(id));
    }

    private ClassResponse toResponse(ClassEntity clazz) {
        return new ClassResponse(clazz.getId(), clazz.getName(), clazz.getCourseId(),
                clazz.getTeacherId(), clazz.getCreatedBy(), clazz.getCreatedTime());
    }
}
