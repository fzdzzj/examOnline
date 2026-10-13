package com.exam.question.controller;

import com.exam.auth.security.RequirePermission;
import com.exam.common.ApiResponse;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.question.dto.QuestionImageUploadResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

/**
 * 题干图片上传端点（add-question-image）：
 * <ul>
 *   <li>{@code POST /api/question-images}：multipart 单文件。类级
 *       {@code @RequirePermission("question:manage")} 复用题库权限点（零 RBAC 表变更）；</li>
 *   <li>安全校验链（全部先于落盘）：扩展名白名单（png/jpg/jpeg/gif/webp）→
 *       魔数校验防伪装（扩展名与文件头逐一对应）→ 单文件 ≤5MB；</li>
 *   <li>服务端生成 UUID 文件名，绝不采纳客户端文件名；存储目录
 *       {@code exam.question-image.dir}（默认 ./data/question-images，环境变量可覆盖）；</li>
 *   <li>返回 {@code {url}}（相对 URL /images/{uuid}.{ext}），下载端点 {@code GET /images/{filename}}
 *       公开只读置 /api 外（见 QuestionImageDownloadController），随机文件名即 capability URL。</li>
 * </ul>
 *
 * <p>边界（proposal 裁决）：不做缩略图/压缩（原图直存，零新依赖）、不做签名/过期、
 * 上传不限流（题目管理低频操作）、孤儿图片不清理（删题/换图后旧文件留存，物理清理另行立项）。
 */
@Slf4j
@RestController
@RequestMapping("/api/question-images")
@RequirePermission("question:manage")
public class QuestionImageController {

    /** 扩展名 -> Content-Type（下载端点按扩展名回 Content-Type 的同源口径）。 */
    private static final Map<String, String> EXT_CONTENT_TYPE = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");

    /** PNG 文件头：89 50 4E 47 0D 0A 1A 0A */
    private static final byte[] MAGIC_PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    /** JPEG 文件头：FF D8 FF */
    private static final byte[] MAGIC_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    /** GIF 文件头：GIF8（87a/89a 共用） */
    private static final byte[] MAGIC_GIF = {'G', 'I', 'F', '8'};
    /** WEBP 文件头：RIFF....WEBP（须 ≥12 字节，第 8-11 字节为 WEBP） */
    private static final byte[] MAGIC_WEBP_RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] MAGIC_WEBP = {'W', 'E', 'B', 'P'};

    private final Path imageDir;
    private final long maxBytes;

    public QuestionImageController(
            @Value("${exam.question-image.dir:./data/question-images}") String imageDir,
            @Value("${exam.question-image.max-size-mb:5}") long maxSizeMb) {
        this.imageDir = Paths.get(imageDir);
        this.maxBytes = maxSizeMb * 1024 * 1024;
    }

    @PostMapping
    public ApiResponse<QuestionImageUploadResponse> upload(
            @RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "请选择图片文件");
        }
        String ext = extensionOf(file.getOriginalFilename());
        String contentType = EXT_CONTENT_TYPE.get(ext);
        if (contentType == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "仅支持 png/jpg/jpeg/gif/webp 格式");
        }
        if (file.isEmpty() || file.getSize() <= 0) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "图片文件为空");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessException(ResponseCode.BAD_REQUEST,
                    "图片大小不能超过 " + (maxBytes / 1024 / 1024) + "MB");
        }
        if (!magicMatches(ext, file)) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "图片内容与扩展名不符");
        }

        // 服务端生成 UUID 文件名（随机文件名即下载端 capability URL，不可枚举）
        String filename = UUID.randomUUID() + "." + ext;
        Path target = imageDir.resolve(filename);
        try {
            Files.createDirectories(imageDir);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("题干图片落盘失败: {}", filename, e);
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "图片保存失败");
        }
        log.info("教师上传题干图片: {} ({})", filename, contentType);
        return ApiResponse.success(new QuestionImageUploadResponse("/images/" + filename));
    }

    /** 客户端文件名 -> 小写扩展名（取最后一个点之后；无点/null 返回空串走白名单拒绝）。 */
    private String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        return dot < 0 || dot == originalFilename.length() - 1
                ? "" : originalFilename.substring(dot + 1).toLowerCase();
    }

    /** 魔数校验：扩展名与文件头逐一对应（防伪装——改扩展名的非图片文件在此被拒）。 */
    private boolean magicMatches(String ext, MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(12);
            switch (ext) {
                case "png" -> {
                    return startsWith(head, MAGIC_PNG);
                }
                case "jpg", "jpeg" -> {
                    return startsWith(head, MAGIC_JPEG);
                }
                case "gif" -> {
                    return startsWith(head, MAGIC_GIF);
                }
                case "webp" -> {
                    return startsWith(head, MAGIC_WEBP_RIFF)
                            && head.length >= 12 && startsWith(head, 8, MAGIC_WEBP);
                }
                default -> {
                    return false;
                }
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean startsWith(byte[] head, byte[] magic) {
        return startsWith(head, 0, magic);
    }

    private static boolean startsWith(byte[] head, int offset, byte[] magic) {
        if (head.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (head[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
