package com.exam.question;

import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 学生题目个人收藏集成测试（add-student-question-favorites）：
 * 验证收藏、取消收藏、分页查询、题型/关键字筛选、批量状态检测及多学生严格隔离。
 */
class StudentQuestionFavoriteIntegrationTest extends IntegrationTestBase {

    @Test
    void favoriteLifecycleAndIdempotence() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();

        long q1 = createQuestion(teacher, 1, "Java 中的基本类型？", "A", List.of("int", "String"), null);
        long q2 = createQuestion(teacher, 3, "Spring 支持 IOC？", "T", null, null);

        // 1. 收藏有效题目
        perform(jsonPost("/api/student/favorites/" + q1, student, null), 200);

        // 2. 重复收藏满足幂等性
        perform(jsonPost("/api/student/favorites/" + q1, student, null), 200);

        // 3. 批量检测收藏状态
        JsonNode checkRes = perform(jsonGet("/api/student/favorites/check?questionIds=" + q1 + "," + q2, student), 200)
                .get("data");
        assertTrue(checkRes.isArray());
        assertEquals(1, checkRes.size());
        assertEquals(q1, checkRes.get(0).asLong());

        // 4. 分页列表查询本人收藏
        JsonNode listRes = perform(jsonGet("/api/student/favorites?page=1&size=10", student), 200)
                .get("data");
        assertEquals(1, listRes.get("total").asLong());
        JsonNode item = listRes.get("list").get(0);
        assertEquals(q1, item.get("questionId").asLong());
        assertEquals("单选", item.get("typeName").asText());
        assertEquals("Java 中的基本类型？", item.get("content").asText());
        assertTrue(item.get("choices").isArray());
        assertEquals("int", item.get("choices").get(0).asText());

        // 5. 取消收藏
        perform(jsonDelete("/api/student/favorites/" + q1, student), 200);

        // 6. 重复取消收藏满足幂等性
        perform(jsonDelete("/api/student/favorites/" + q1, student), 200);

        // 7. 再次检测与查询
        JsonNode checkAfter = perform(jsonGet("/api/student/favorites/check?questionIds=" + q1, student), 200)
                .get("data");
        assertEquals(0, checkAfter.size());

        JsonNode listAfter = perform(jsonGet("/api/student/favorites?page=1&size=10", student), 200)
                .get("data");
        assertEquals(0, listAfter.get("total").asLong());
    }

    @Test
    void favoriteNotFoundAndSoftDeleted_returns404() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();

        long qDel = createQuestion(teacher, 1, "即将软删除的题目", "A", List.of("选项A", "选项B"), null);

        // 收藏不存在的题目 -> 404
        perform(jsonPost("/api/student/favorites/99999999", student, null), 404);

        // 教师软删除题目
        perform(jsonDelete("/api/questions/" + qDel, teacher), 200);

        // 收藏已软删除题目 -> 404
        perform(jsonPost("/api/student/favorites/" + qDel, student, null), 404);
    }

    @Test
    void studentDataIsolation_strictSeparation() throws Exception {
        String teacher = registerTeacher();
        String stuA = registerStudent();
        String stuB = registerStudent();

        long qA = createQuestion(teacher, 1, "属于A的题目", "A", List.of("A1", "A2"), null);
        long qB = createQuestion(teacher, 2, "属于B的题目", "A,B", List.of("B1", "B2"), null);

        // A 收藏 qA，B 收藏 qB
        perform(jsonPost("/api/student/favorites/" + qA, stuA, null), 200);
        perform(jsonPost("/api/student/favorites/" + qB, stuB, null), 200);

        // A 查询列表仅见 qA
        JsonNode listA = perform(jsonGet("/api/student/favorites", stuA), 200).get("data");
        assertEquals(1, listA.get("total").asLong());
        assertEquals(qA, listA.get("list").get(0).get("questionId").asLong());

        // B 查询列表仅见 qB
        JsonNode listB = perform(jsonGet("/api/student/favorites", stuB), 200).get("data");
        assertEquals(1, listB.get("total").asLong());
        assertEquals(qB, listB.get("list").get(0).get("questionId").asLong());

        // 批量检测状态隔离
        JsonNode checkA = perform(jsonGet("/api/student/favorites/check?questionIds=" + qA + "," + qB, stuA), 200)
                .get("data");
        assertEquals(1, checkA.size());
        assertEquals(qA, checkA.get(0).asLong());

        JsonNode checkB = perform(jsonGet("/api/student/favorites/check?questionIds=" + qA + "," + qB, stuB), 200)
                .get("data");
        assertEquals(1, checkB.size());
        assertEquals(qB, checkB.get(0).asLong());
    }

    @Test
    void listFavoritesFilterAndSearch() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();

        long qSingle = createQuestion(teacher, 1, "算法导论单选题", "A", List.of("对", "错"), null);
        long qJudge = createQuestion(teacher, 3, "操作系统判断题", "T", null, null);

        perform(jsonPost("/api/student/favorites/" + qSingle, student, null), 200);
        perform(jsonPost("/api/student/favorites/" + qJudge, student, null), 200);

        // 1. 题型筛选：仅查判断题 (type=3)
        JsonNode typeFiltered = perform(jsonGet("/api/student/favorites?type=3", student), 200).get("data");
        assertEquals(1, typeFiltered.get("total").asLong());
        assertEquals(qJudge, typeFiltered.get("list").get(0).get("questionId").asLong());

        // 2. 关键字检索：按 "算法"
        JsonNode keywordFiltered = perform(jsonGet("/api/student/favorites?keyword=算法", student), 200).get("data");
        assertEquals(1, keywordFiltered.get("total").asLong());
        assertEquals(qSingle, keywordFiltered.get("list").get(0).get("questionId").asLong());
    }
}
