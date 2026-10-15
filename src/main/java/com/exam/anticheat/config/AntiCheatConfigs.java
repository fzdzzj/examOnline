package com.exam.anticheat.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code exams.anti_cheat_config} JSON 配置的读取口径（spec add-anti-cheat-restrictions，
 * 零 DDL：配置复用既有 TEXT 列，不新增表/列）。
 *
 * <p>该列由建考表单写入、由快照发布原样嵌入，历史与脏数据都可能存在，
 * 因此读取一律遵循"脏配置不阻断、不误伤"原则：
 * <ul>
 *   <li>非法 JSON → 返回 {@code null}（等价于未配置防作弊，正常放行并留 warn 日志）；</li>
 *   <li>{@code ipWhitelist} 缺失、非数组、空数组 → 视为未配置白名单（全网放行）——
 *       <b>绝不因脏配置把整场考生挡在门外</b>；</li>
 *   <li>数组内非字符串项与空白项跳过，重复项去重并保持声明顺序（便于日志与前端回显）。</li>
 * </ul>
 */
@Slf4j
public final class AntiCheatConfigs {

    /** IP 白名单字段名（与建考表单/前端守卫共用同一口径）。 */
    public static final String FIELD_IP_WHITELIST = "ipWhitelist";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AntiCheatConfigs() {
    }

    /** 解析防作弊配置：空白或非法返回 {@code null}（调用方按"未配置"处理），不抛异常。 */
    public static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            // 脏数据只降级不阻断：与 ExamSnapshotService 的既有口径一致
            log.warn("防作弊配置 JSON 解析失败，按未配置处理: {}", json);
            return null;
        }
    }

    /**
     * 提取 IP 白名单条目：规范化后的不可变列表；未配置（缺失/非数组/空数组/全空白）返回空列表。
     * 空列表即"不做 IP 限制"，是否放行由调用方决定。
     */
    public static List<String> ipWhitelist(JsonNode config) {
        if (config == null || config.isNull()) {
            return List.of();
        }
        JsonNode node = config.get(FIELD_IP_WHITELIST);
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (item == null || !item.isTextual()) {
                continue;
            }
            String entry = item.asText().trim();
            if (!entry.isEmpty()) {
                unique.add(entry);
            }
        }
        return List.copyOf(unique);
    }
}
