package com.exam.question.controller;

import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import com.exam.question.dto.QuestionImportResult;
import com.exam.question.service.QuestionImportExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 题库批量导入/导出/模板端点（add-question-import-export）。
 *
 * <p>类级 {@code @RequirePermission("question:manage")} 复用题库权限点（零 RBAC 表变更）——
 * 学生/未持有权限者三端点均被拦截（403）；未登录 401。与 {@link QuestionController}
 * 同属 /api/questions 命名空间，各方法路径无冲突。
 *
 * <p>导入为 multipart 单文件 xlsx，契约口径对齐 add-question-image 修复终态：
 * {@code @RequestPart("file")} + {@code consumes = multipart/form-data}，使 springdoc 如实导出
 * multipart/form-data + binary。导出/模板为 xlsx 附件下载，attachment 响应对齐既有成绩导出形态。
 */
@RestController
@RequestMapping("/api/questions")
@RequirePermission("question:manage")
public class QuestionImportExportController {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final QuestionImportExportService importExportService;

    public QuestionImportExportController(QuestionImportExportService importExportService) {
        this.importExportService = importExportService;
    }

    /** 批量导入：multipart xlsx，逐行容错，返回 {imported, failed, errors}。 */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<QuestionImportResult> importQuestions(
            @RequestPart(value = "file", required = false) MultipartFile file) {
        return ApiResponse.success(importExportService.importQuestions(file));
    }

    /** 全量导出未软删题目（xlsx 附件，列口径与模板逐字一致）。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportQuestions() {
        return attachment("题库导出", ".xlsx",
                importExportService.exportQuestions());
    }

    /** 下载导入模板（xlsx 附件，表头 + 五题型示例行 + 填写说明）。 */
    @GetMapping("/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        return attachment("题库导入模板", ".xlsx",
                importExportService.buildTemplate());
    }

    /** 下载响应：Content-Disposition 文件名带时间戳（RFC 5987 UTF-8 编码，对齐成绩导出）。 */
    private static ResponseEntity<byte[]> attachment(String name, String ext, byte[] body) {
        String filename = URLEncoder.encode(
                name + "-" + System.currentTimeMillis() + ext, StandardCharsets.UTF_8);
        return ResponseEntity.status(org.springframework.http.HttpStatus.OK)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(XLSX_MEDIA_TYPE))
                .body(body);
    }
}