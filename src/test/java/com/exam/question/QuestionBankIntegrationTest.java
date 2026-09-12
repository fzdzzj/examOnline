package com.exam.question;

import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题库集成测试（spec「题目管理/答案归一化/标签体系/越权防护」）：
 * 四题型 CRUD 与答案归一化、分页筛选、扁平标签体系、教师间水平越权 403、
 * 学生无权限 403、软删除后不再可见。
 */
class QuestionBankIntegrationTest extends IntegrationTestBase {

    @Test
    void questionCrudNormalizesAnswers() throws Exception {
        String teacher = registerTeacher();

        // 单选：小写字母归一为大写
        JsonNode single = perform(jsonPost("/api/questions", teacher,
                questionJson(1, "下面哪个是红色?", "b", List.of("红", "绿", "蓝"), 5.0, 1, null)), 200)
                .get("data");
        assertEquals("B", single.get("correctAnswer").asText());
        assertEquals("单选", single.get("typeName").asText());

        // 多选：乱序+重复+大小写混杂 -> 去重升序
        JsonNode multi = perform(jsonPost("/api/questions", teacher,
                questionJson(2, "下列哪些是颜色?", "d, c, B, a", List.of("红", "绿", "蓝", "黄"), 5.0, 1, null)), 200)
                .get("data");
        assertEquals("A,B,C,D", multi.get("correctAnswer").asText());

        // 判断：中文别名 -> T/F
        JsonNode judge = perform(jsonPost("/api/questions", teacher,
                questionJson(3, "1+1=2", "正确", null, 5.0, 1, null)), 200).get("data");
        assertEquals("T", judge.get("correctAnswer").asText());

        // 简答：参考答案保留原文（仅去空白）
        JsonNode shortAnswer = perform(jsonPost("/api/questions", teacher,
                questionJson(4, "简述 TCP 三次握手", "  三次握手内容  ", null, 10.0, 2, null)), 200).get("data");
        assertEquals("三次握手内容", shortAnswer.get("correctAnswer").asText());

        // 更新后重走归一化：乱序答案 -> 有序存储
        JsonNode updated = perform(jsonPut("/api/questions/" + multi.get("id").asLong(), teacher,
                questionJson(2, "下列哪些是颜色?", "B,A", List.of("红", "绿", "蓝", "黄"), 5.0, 1, null)), 200)
                .get("data");
        assertEquals("A,B", updated.get("correctAnswer").asText());

        // 非法答案被拒：单选超范围、判断非法值、客观题选项不足
        perform(jsonPost("/api/questions", teacher,
                questionJson(1, "范围外", "E", List.of("甲", "乙"), 5.0, 1, null)), 400);
        perform(jsonPost("/api/questions", teacher,
                questionJson(3, "非法判断", "C", null, 5.0, 1, null)), 400);
        perform(jsonPost("/api/questions", teacher,
                questionJson(1, "选项不足", "A", List.of("唯一选项"), 5.0, 1, null)), 400);

        // 软删除：详情 404、列表不再出现
        perform(jsonDelete("/api/questions/" + judge.get("id").asLong(), teacher), 200);
        perform(jsonGet("/api/questions/" + judge.get("id").asLong(), teacher), 404);
        JsonNode page = perform(jsonGet("/api/questions?type=3", teacher), 200).get("data");
        assertEquals(0, page.get("total").asLong());
    }

    @Test
    void questionPageFiltersByTypeKeywordAndPaged() throws Exception {
        String teacher = registerTeacher();

        createQuestion(teacher, 1, "Java 单选题甲", "A", List.of("A选项", "B选项"), null);
        createQuestion(teacher, 1, "Java 单选题乙", "B", List.of("A选项", "B选项"), null);
        createQuestion(teacher, 3, "Java 判断题丙", "对", null, null);

        // 按题型筛选
        assertEquals(2, perform(jsonGet("/api/questions?type=1&size=10", teacher), 200)
                .get("data").get("total").asLong());
        assertEquals(1, perform(jsonGet("/api/questions?type=3", teacher), 200)
                .get("data").get("total").asLong());
        // 关键词筛选
        assertEquals(1, perform(jsonGet("/api/questions?keyword=乙", teacher), 200)
                .get("data").get("total").asLong());
        // 分页：size=2 只返回 2 条且 total 仍为 3
        JsonNode page = perform(jsonGet("/api/questions?page=1&size=2", teacher), 200).get("data");
        assertEquals(3, page.get("total").asLong());
        assertEquals(2, page.get("list").size());
    }

    @Test
    void tagSystemSupportsQuestionAssociationAndFiltering() throws Exception {
        String teacher = registerTeacher();
        String mathName = unique("数学");
        long mathTag = createTag(teacher, mathName, "SUBJECT");
        long easyTag = createTag(teacher, unique("简单"), "DIFFICULTY");

        // 同类型同名重复创建 -> 1001
        JsonNode duplicate = perform(jsonPost("/api/tags", teacher,
                "{\"name\":\"" + mathName + "\",\"type\":\"SUBJECT\"}"), 400);
        assertEquals(1001, duplicate.get("code").asInt());
        // 非法类型被拒
        perform(jsonPost("/api/tags", teacher, "{\"name\":\"x\",\"type\":\"FOO\"}"), 400);

        // 列表按类型过滤（标签全局共享、H2 数据跨用例保留，断言包含新建标签而非精确数量）
        JsonNode subjects = perform(jsonGet("/api/tags?type=SUBJECT", teacher), 200).get("data");
        boolean containsMathTag = false;
        for (JsonNode tag : subjects) {
            if (tag.get("id").asLong() == mathTag) {
                containsMathTag = true;
            }
        }
        assertTrue(containsMathTag);

        // 题目多标签关联（学科 + 难度同时挂）
        long questionId = createQuestion(teacher, 1, "带标签题", "A", List.of("A选项", "B选项"),
                List.of(mathTag, easyTag));
        JsonNode detail = perform(jsonGet("/api/questions/" + questionId, teacher), 200).get("data");
        assertEquals(2, detail.get("tags").size());

        // 按标签筛选题目
        assertEquals(1, perform(jsonGet("/api/questions?tagId=" + mathTag, teacher), 200)
                .get("data").get("total").asLong());

        // 更新题目标签：替换关联
        long customTag = createTag(teacher, unique("期末"), "CUSTOM");
        perform(jsonPut("/api/questions/" + questionId, teacher,
                questionJson(1, "带标签题", "A", List.of("A选项", "B选项"), 5.0, 1, List.of(customTag))), 200);
        JsonNode afterUpdate = perform(jsonGet("/api/questions/" + questionId, teacher), 200).get("data");
        assertEquals("CUSTOM", afterUpdate.get("tags").get(0).get("type").asText());
        assertEquals(0, perform(jsonGet("/api/questions?tagId=" + mathTag, teacher), 200)
                .get("data").get("total").asLong());

        // 删除标签：关联清理，按其筛选不再命中
        perform(jsonDelete("/api/tags/" + customTag, teacher), 200);
        assertEquals(0, perform(jsonGet("/api/questions?tagId=" + customTag, teacher), 200)
                .get("data").get("total").asLong());
        perform(jsonDelete("/api/tags/" + customTag, teacher), 404);
    }

    @Test
    void ownershipIsolationAndRolePermission() throws Exception {
        String teacherA = registerTeacher();
        String teacherB = registerTeacher();
        long questionId = createQuestion(teacherA, 1, "A 的题目", "A", List.of("A选项", "B选项"), null);

        // 教师 B 越权读写教师 A 的题目 -> 403
        perform(jsonGet("/api/questions/" + questionId, teacherB), 403);
        perform(jsonPut("/api/questions/" + questionId, teacherB,
                questionJson(1, "篡改", "B", List.of("A选项", "B选项"), 5.0, 1, null)), 403);
        perform(jsonDelete("/api/questions/" + questionId, teacherB), 403);

        // ADMIN 越级放行（层级 3）
        JsonNode updated = perform(jsonPut("/api/questions/" + questionId, loginAdminToken(),
                questionJson(1, "管理员修订", "A", List.of("A选项", "B选项"), 5.0, 1, null)), 200)
                .get("data");
        assertEquals("管理员修订", updated.get("content").asText());

        // 学生无 question:manage 权限点 -> 403
        String student = registerStudent();
        perform(jsonPost("/api/questions", student,
                questionJson(1, "学生建题", "A", List.of("A选项", "B选项"), 5.0, 1, null)), 403);
        perform(jsonGet("/api/questions", student), 403);

        // 教师列表只含个人题库（B 看不到 A 的题目）
        assertEquals(0, perform(jsonGet("/api/questions", teacherB), 200).get("data").get("total").asLong());
        assertEquals(1, perform(jsonGet("/api/questions", teacherA), 200).get("data").get("total").asLong());

        // 归属教师可见自己的分值与归一化答案
        JsonNode own = perform(jsonGet("/api/questions/" + questionId, teacherA), 200).get("data");
        assertFalse(own.get("score").isNull());
        assertTrue(own.get("correctAnswer").asText().length() > 0);
    }

    private String loginAdminToken() throws Exception {
        return perform(jsonPost("/api/auth/login", null,
                "{\"username\":\"admin\",\"password\":\"admin123\"}"), 200)
                .get("data").get("accessToken").asText();
    }
}
