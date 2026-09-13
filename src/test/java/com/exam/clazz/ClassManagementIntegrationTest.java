package com.exam.clazz;

import com.exam.clazz.service.ClassService;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 班级管理集成测试（spec「班级管理」：班级实体 + 学生入班/转班 + 班级学生列表）。
 *
 * <p>测试库（H2）只加载 schema.sql，classes/user_class 由本类用 @Sql 幂等补建
 * （DDL 与迁移文件 docker/mysql/migrations/2026-W10-add-class.sql 对齐，仅测试环境自举用）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>班级 CRUD（创建/详情/部分更新/分页/软删）；</li>
 *   <li>越权防护：跨教师 403、学生 403、ADMIN 越级可见；</li>
 *   <li>入班/重复入班 1001/移除；</li>
 *   <li>转班后 listStudentIds 随人迁移（成绩随人 §12.6 的名单视角）；</li>
 *   <li>班级学生列表（含学号/姓名/入班时间）。</li>
 * </ul>
 */
@Sql(statements = {
        // 与迁移文件 2026-W10-add-class.sql 的建表定义一致（H2 单行书写以兼容 @Sql 解析）
        "CREATE TABLE IF NOT EXISTS classes (id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(64) NOT NULL, "
                + "course_id BIGINT DEFAULT NULL, teacher_id BIGINT NOT NULL, created_by BIGINT NOT NULL, "
                + "created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, is_deleted TINYINT NOT NULL DEFAULT 0, "
                + "KEY idx_classes_teacher (teacher_id))",
        "CREATE TABLE IF NOT EXISTS user_class (id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, "
                + "class_id BIGINT NOT NULL, joined_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "CONSTRAINT uk_user_class UNIQUE (user_id, class_id), KEY idx_class_id (class_id))"
})
class ClassManagementIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ClassService classService;

    @Test
    void classCrudPageAndOwnerCheck() throws Exception {
        String teacherA = registerTeacher();
        String teacherB = registerTeacher();
        String student = registerStudent();
        long teacherAId = userIdOf(teacherA);

        // 创建班级（带课程 ID）
        long classId = createClass(teacherA, "一班", 101L);

        // 详情：归属教师与创建人均为当前教师
        JsonNode detail = perform(jsonGet("/api/classes/" + classId, teacherA), 200).get("data");
        assertEquals("一班", detail.get("name").asText());
        assertEquals(101, detail.get("courseId").asLong());
        assertEquals(teacherAId, detail.get("teacherId").asLong());
        assertEquals(teacherAId, detail.get("createdBy").asLong());

        // 部分更新：只改名字，课程 ID 保留
        perform(jsonPut("/api/classes/" + classId, teacherA, "{\"name\":\"一班-改名\"}"), 200);
        detail = perform(jsonGet("/api/classes/" + classId, teacherA), 200).get("data");
        assertEquals("一班-改名", detail.get("name").asText());
        assertEquals(101, detail.get("courseId").asLong());

        // 分页：教师只见自己的班级，ADMIN 可见全部
        assertTrue(containsClass(perform(jsonGet("/api/classes?size=100", teacherA), 200).get("data"), classId));
        assertFalse(containsClass(perform(jsonGet("/api/classes?size=100", teacherB), 200).get("data"), classId));
        assertTrue(containsClass(perform(jsonGet("/api/classes?size=100", adminToken()), 200).get("data"), classId));

        // 越权防护：跨教师 403（owner 校验，复用 OwnershipGuard 模式）
        perform(jsonGet("/api/classes/" + classId, teacherB), 403);
        perform(jsonPut("/api/classes/" + classId, teacherB, "{\"name\":\"篡改\"}"), 403);
        perform(jsonDelete("/api/classes/" + classId, teacherB), 403);
        perform(jsonPost("/api/classes/" + classId + "/students", teacherB, "{\"userId\":1}"), 403);

        // 学生无班级管理权限（@RequireRole(TEACHER)）
        perform(jsonPost("/api/classes", student, "{\"name\":\"越权班\"}"), 403);

        // 软删除：详情 404、列表不再出现
        perform(jsonDelete("/api/classes/" + classId, teacherA), 200);
        perform(jsonGet("/api/classes/" + classId, teacherA), 404);
        assertFalse(containsClass(perform(jsonGet("/api/classes?size=100", teacherA), 200).get("data"), classId));
    }

    @Test
    void joinTransferRemoveAndStudentList() throws Exception {
        String teacher = registerTeacher();
        String stu1 = registerStudent();
        long stu1Id = userIdOf(stu1);
        long classA = createClass(teacher, "甲班", null);
        long classB = createClass(teacher, "乙班", null);

        // 入班：listStudentIds 命中
        perform(jsonPost("/api/classes/" + classA + "/students", teacher, "{\"userId\":" + stu1Id + "}"), 200);
        assertTrue(classService.listStudentIds(classA).contains(stu1Id));

        // 重复入班 → 1001
        JsonNode dup = perform(jsonPost("/api/classes/" + classA + "/students", teacher,
                "{\"userId\":" + stu1Id + "}"), 400);
        assertEquals(1001, dup.get("code").asInt());

        // 不存在的学生入班 → 404
        perform(jsonPost("/api/classes/" + classA + "/students", teacher, "{\"userId\":999999}"), 404);

        // 班级学生列表：含学号/姓名/入班时间
        JsonNode list = perform(jsonGet("/api/classes/" + classA + "/students", teacher), 200).get("data");
        assertEquals(1, list.size());
        assertEquals(stu1Id, list.get(0).get("userId").asLong());
        assertTrue(list.get(0).get("username").asText().startsWith("stu_"));
        assertNotNull(list.get(0).get("name").asText());
        assertNotNull(list.get(0).get("joinedTime"));

        // 转班（A → B）：转班只改 user_class.class_id，listStudentIds 随人迁移——
        // 名单推导以当前班级为准，与"成绩随人"（§12.6）同一数据模型
        perform(jsonPut("/api/classes/" + classA + "/students/" + stu1Id + "/transfer", teacher,
                "{\"targetClassId\":" + classB + "}"), 200);
        assertFalse(classService.listStudentIds(classA).contains(stu1Id));
        assertTrue(classService.listStudentIds(classB).contains(stu1Id));

        // 转班后目标班列表含该生（joinedTime 刷新为转入时间）
        JsonNode listB = perform(jsonGet("/api/classes/" + classB + "/students", teacher), 200).get("data");
        assertEquals(1, listB.size());
        assertEquals(stu1Id, listB.get(0).get("userId").asLong());

        // 目标班级不存在 → 404
        perform(jsonPut("/api/classes/" + classA + "/students/" + stu1Id + "/transfer", teacher,
                "{\"targetClassId\":999999}"), 404);

        // 移除：B 中移出后名单为空
        perform(jsonDelete("/api/classes/" + classB + "/students/" + stu1Id, teacher), 200);
        assertFalse(classService.listStudentIds(classB).contains(stu1Id));
    }

    // ==================== 工具 ====================

    private long createClass(String token, String name, Long courseId) throws Exception {
        String json = courseId == null
                ? "{\"name\":\"" + name + "\"}"
                : "{\"name\":\"" + name + "\",\"courseId\":" + courseId + "}";
        return perform(jsonPost("/api/classes", token, json), 200).get("data").get("id").asLong();
    }

    /** 当前登录用户 ID（/api/auth/me）。 */
    private long userIdOf(String token) throws Exception {
        return perform(jsonGet("/api/auth/me", token), 200).get("data").get("id").asLong();
    }

    /** 管理员 Access Token（IntegrationTestBase 的 loginToken 为私有，此处自建）。 */
    private String adminToken() throws Exception {
        return perform(jsonPost("/api/auth/login", null,
                "{\"username\":\"admin\",\"password\":\"admin123\"}"), 200)
                .get("data").get("accessToken").asText();
    }

    private boolean containsClass(JsonNode page, long classId) {
        for (JsonNode item : page.get("list")) {
            if (item.get("id").asLong() == classId) {
                return true;
            }
        }
        return false;
    }
}
