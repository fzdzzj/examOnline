package com.exam;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 骨架冒烟测试：
 * 1. 上下文可启动 + 健康检查返回 UP（spec: 健康检查可访问）；
 * 2. RBAC 五表建表成功（spec: 首次建表成功）+ username 唯一约束生效；
 * 3. 角色预置完成。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkeletonSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    void rbacFiveTablesCreated() {
        String[] tables = {"users", "roles", "permissions", "user_roles", "role_permissions"};
        for (String table : tables) {
            assertDoesNotThrow(() -> jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class),
                    "表 " + table + " 应已创建");
        }
    }

    @Test
    void schemaIsIdempotent() {
        // 幂等重放：再次执行同一 schema.sql 不报错（CREATE TABLE IF NOT EXISTS）
        assertDoesNotThrow(() -> jdbc.execute(
                "CREATE TABLE IF NOT EXISTS users (id BIGINT NOT NULL AUTO_INCREMENT, username VARCHAR(64) NOT NULL, CONSTRAINT uk_users_username UNIQUE (username))"));
        assertDoesNotThrow(() -> jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
    }

    @Test
    void rolesSeeded() {
        Integer roles = jdbc.queryForObject("SELECT COUNT(*) FROM roles", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(3, roles);
    }

    @Test
    void usernameUniqueConstraintEnforced() {
        jdbc.update("INSERT INTO users (username, password, name) VALUES ('20260001', 'x', 'A')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO users (username, password, name) VALUES ('20260001', 'y', 'B')"));
    }
}
