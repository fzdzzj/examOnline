package com.exam.user.controller;

import com.exam.auth.security.RequirePermission;
import com.exam.auth.security.RequireRole;
import com.exam.auth.security.RoleHierarchy;
import com.exam.common.ApiResponse;
import com.exam.user.dto.UserImportResult;
import com.exam.user.service.UserImportExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 用户/学生批量导入导出控制器（add-user-import-export）。
 *
 * <p>类级 {@code @RequireRole(RoleHierarchy.ADMIN)} + 方法级 {@code @RequirePermission("user:manage")}
 * 对齐 AdminController 权限形态。
 */
@RestController
@RequestMapping("/api/admin/users")
@Validated
@RequireRole(RoleHierarchy.ADMIN)
public class UserImportExportController {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final UserImportExportService importExportService;

    public UserImportExportController(UserImportExportService importExportService) {
        this.importExportService = importExportService;
    }

    /** 批量导入学生：multipart xlsx，逐行容错，返回 {imported, failed, errors}。 */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission("user:manage")
    public ApiResponse<UserImportResult> importUsers(
            @RequestPart(value = "file", required = false) MultipartFile file) {
        return ApiResponse.success(importExportService.importUsers(file));
    }

    /** 下载学生导入模板（xlsx 附件）。 */
    @GetMapping("/import-template")
    @RequirePermission("user:manage")
    public ResponseEntity<byte[]> importTemplate() {
        return attachment("用户导入模板", ".xlsx",
                importExportService.buildTemplate());
    }

    /**
     * 导出用户名单（xlsx 附件，SXSSF 流式）。
     *
     * @param scope   student|teacher|all（默认 student）
     * @param classId 可选班级过滤（仅在 scope=student 时有效）
     */
    @GetMapping("/export")
    @RequirePermission("user:manage")
    public ResponseEntity<byte[]> exportUsers(
            @RequestParam(defaultValue = "student") String scope,
            @RequestParam(required = false) Long classId) {
        return attachment("用户导出", ".xlsx",
                importExportService.exportUsers(scope, classId));
    }

    private static ResponseEntity<byte[]> attachment(String name, String ext, byte[] body) {
        String filename = URLEncoder.encode(
                name + "-" + System.currentTimeMillis() + ext, StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(XLSX_MEDIA_TYPE))
                .body(body);
    }
}
