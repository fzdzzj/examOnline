package com.exam.clazz;

import com.exam.clazz.service.ClassService;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 班级管理集成测试（spec「班级管理」：班级实体 + 学生入班/转班 + 班级学生列表）。
 *
 * <p>测试库（H2）由 schema.sql 建全（含 classes/user_class），本类不自行建表。
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
class ClassManagementIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ClassService classService;

    @Autowired
    private ExamMapper examMapper;

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

    /**
     * H-01（harden-core-security-and-state-boundaries）：学生转班目标班级所有权水平越权拦截。
     * 目标班级仅校验存在性时，教师 A 可把学生强行转入教师 B 的班级（越权漏洞）——
     * 实施前（期望 403 得 200）即红；源班级与目标班级相同须 400 防呆。
     */
    @Test
    void transferRejectsCrossTeacherTargetAndSameClass() throws Exception {
        String teacherA = registerTeacher();
        String teacherB = registerTeacher();
        String student = registerStudent();
        long studentId = userIdOf(student);
        long classA = createClass(teacherA, "甲班", null);
        long classB = createClass(teacherB, "乙班", null);

        // 学生先在 A 班（教师 A 入班）
        perform(jsonPost("/api/classes/" + classA + "/students", teacherA, "{\"userId\":" + studentId + "}"), 200);

        // 水平越权：目标班级属于教师 B，教师 A 转班 → 403（目标班级所有权校验缺失即得 200）
        perform(jsonPut("/api/classes/" + classA + "/students/" + studentId + "/transfer", teacherA,
                "{\"targetClassId\":" + classB + "}"), 403);

        // 越权请求不得生效：学生仍在 A 班
        assertTrue(classService.listStudentIds(classA).contains(studentId));

        // 防呆：源班级与目标班级相同 → 400
        perform(jsonPut("/api/classes/" + classA + "/students/" + studentId + "/transfer", teacherA,
                "{\"targetClassId\":" + classA + "}"), 400);
    }

    /**
     * L-01（harden-cross-cutting-edge-cases）：班级被考试引用时阻断删除（400）。
     */
    @Test
    void deleteClassBlockedWhenExamReferencesExist() throws Exception {
        String teacher = registerTeacher();
        long classId = createClass(teacher, "引用测试班", null);

        Exam exam = new Exam();
        exam.setTitle("班级关联期末考");
        exam.setClassId(classId);
        exam.setPaperId(1L);
        exam.setStartTime(LocalDateTime.now().plusDays(1));
        exam.setEndTime(LocalDateTime.now().plusDays(1).plusHours(2));
        exam.setDurationMinutes(120);
        exam.setStatus(Exam.STATUS_NOT_STARTED);
        exam.setCreatedBy(userIdOf(teacher));
        examMapper.insert(exam);

        JsonNode err = perform(jsonDelete("/api/classes/" + classId, teacher), 400);
        assertTrue(err.get("message").asText().contains("该班级已被考试引用，不允许删除"));

        JsonNode detail = perform(jsonGet("/api/classes/" + classId, teacher), 200).get("data");
        assertEquals("引用测试班", detail.get("name").asText());
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
