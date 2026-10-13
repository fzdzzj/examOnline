package com.exam.question.controller;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 题干图片下载端点（add-question-image）：
 * <ul>
 *   <li>{@code GET /images/{filename}} 公开只读，<b>置于 /api 外</b>——天然不经
 *       AuthenticationInterceptor（该拦截器只拦 /api/**），auth/security 零改动；</li>
 *   <li>文件名白名单正则（严格 UUID + 白名单扩展名）防路径穿越：任何不含法 UUID 形态的
 *       文件名一律 404，即使同名文件真实存在于存储目录也不被服务；</li>
 *   <li>按扩展名回 Content-Type（与上传端点同源口径）；查无文件 404 兜底；</li>
 *   <li>springdoc paths-to-match=/api/** → 本端点不进 OpenAPI 契约（paths 仅 +1 上传端点）。</li>
 * </ul>
 *
 * <p>安全说明（proposal 裁决）：随机 UUID 文件名即 capability URL（不可枚举），
 * 不另做签名/过期；题库图片敏感度可接受公开只读。
 */
@Slf4j
@RestController
public class QuestionImageDownloadController {

    private static final Map<String, String> EXT_CONTENT_TYPE = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");

    /** 文件名白名单：8-4-4-4-12 十六进制 UUID + 白名单扩展名（大小写不敏感）。 */
    private static final Pattern FILENAME_PATTERN = Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg|jpeg|gif|webp)$");

    private final Path imageDir;

    public QuestionImageDownloadController(
            @Value("${exam.question-image.dir:./data/question-images}") String imageDir) {
        this.imageDir = Paths.get(imageDir);
    }

    @GetMapping("/images/{filename}")
    public ResponseEntity<byte[]> download(@PathVariable String filename) {
        // 文件名白名单防路径穿越：非 UUID+白名单扩展名形态一律 404（不落盘探查，防目录遍历探测）
        if (!FILENAME_PATTERN.matcher(filename).matches()) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "图片不存在");
        }
        Path file = imageDir.resolve(filename).normalize();
        // 纵深防御：normalize 后仍须落在存储目录内（白名单已保证单段文件名，此检查为兜底）
        if (!file.startsWith(imageDir.normalize())) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "图片不存在");
        }
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "图片不存在");
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
            String contentType = EXT_CONTENT_TYPE.getOrDefault(ext, MediaType.APPLICATION_OCTET_STREAM_VALUE);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType)).body(bytes);
        } catch (IOException e) {
            log.error("题干图片读取失败: {}", filename, e);
            throw new BusinessException(ResponseCode.NOT_FOUND, "图片不存在");
        }
    }
}
