package com.exam.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * SpringDoc OpenAPI 配置（add-backend-openapi 阶段18）。
 * 目的：让后端暴露 /v3/api-docs 作为前端唯一契约来源，避免手写 API 层与后端漂移。
 * - 定义 Bearer JWT SecurityScheme（Authorization 头）
 * - 全局应用安全需求，使生成的客户端默认携带鉴权语义
 * - 公开端点显式标注 security: [] 覆盖全局，实现免鉴权端点在契约中可识别
 * - 注释强调与 WebMvcConfig 同步
 */
@Configuration
public class SpringDocConfig {

    /**
     * 公开端点清单（/api/** 范围内）。
     * 与 com.exam.auth.security.WebMvcConfig.addInterceptors 的 excludePathPatterns 保持同步；
     * 改了白名单必须同步这里并重新导出契约。
     * actuator/** 不在 springdoc.paths-to-match=/api/** 范围内，故不列。
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/password/reset-code",
            "/api/auth/password/reset"
    );

    @Bean
    public OpenAPI examOnlineOpenAPI() {
        final String securitySchemeName = "Authorization";
        return new OpenAPI()
                .info(new Info()
                        .title("examOnline API")
                        .version("1.0")
                        .description("在线考试系统后端 API 契约。用于前端 @hey-api/openapi-ts 生成类型化 axios 客户端。"
                                + "契约如实描述当前 14 个 Controller 的现状，不改任何签名/DTO/逻辑。"
                                + "新增端点后必须重新运行导出命令更新 openapi.yaml。"))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT Access Token，格式：Bearer <token>")));
    }

    @Bean
    public OpenApiCustomizer publicEndpointCustomizer() {
        return openApi -> {
            Paths paths = openApi.getPaths();
            if (paths == null) return;
            for (var entry : paths.entrySet()) {
                String path = entry.getKey();
                if (PUBLIC_PATHS.contains(path)) {
                    PathItem pathItem = entry.getValue();
                    // 对该路径下的所有 HTTP 操作（get/post/put/...）显式设 security 为空列表
                    // 空列表 = 声明“此操作无需鉴权”，覆盖全局 securityRequirement
                    if (pathItem.getGet() != null) pathItem.getGet().setSecurity(List.of());
                    if (pathItem.getPost() != null) pathItem.getPost().setSecurity(List.of());
                    if (pathItem.getPut() != null) pathItem.getPut().setSecurity(List.of());
                    if (pathItem.getDelete() != null) pathItem.getDelete().setSecurity(List.of());
                    if (pathItem.getPatch() != null) pathItem.getPatch().setSecurity(List.of());
                    if (pathItem.getOptions() != null) pathItem.getOptions().setSecurity(List.of());
                    if (pathItem.getHead() != null) pathItem.getHead().setSecurity(List.of());
                    if (pathItem.getTrace() != null) pathItem.getTrace().setSecurity(List.of());
                }
            }
        };
    }
}
