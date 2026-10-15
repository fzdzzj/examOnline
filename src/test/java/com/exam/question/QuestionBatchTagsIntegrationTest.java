package com.exam.question;

import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题库批量打标/解绑集成测试（add-batch-tagging-and-score-balancing）：
 * BIND 差集幂等写入并保留原题其余标签、UNBIND 物理删除所选关联且非选标签保留、
 * 越权题目计入 failed（ADMIN 放行）、空/重复/非法 action 400、批量刷新后可读最新标签。
 * 全类不自行建表——建表以 schema.sql 为准，测试禁 @Sql。
 */
class QuestionBatchTagsIntegrationTest extends IntegrationTestBase {

    /** 与单题路径 OwnershipGuard.assertOwner（resourceName="题目"）的 403 文案一致。 */
    private static final String REASON_FORBIDDEN = "无权操作该题目（资源不属于当前用户）";
    /** 与单题路径 getOwnedQuestion 404 文案一致。 */
    private static final String REASON_NOT_FOUND = "题目不存在";

    /** 批量标签请求体：{"questionIds":[...],"tagIds":[...],"action":"..."}。 */
    private String batchTagsBody(String action, long[] questionIds, long... tagIds) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode q = body.putArray("questionIds");
        for (long id : questionIds) {
            q.add(id);
        }
        ArrayNode t = body.putArray("tagIds");
        for (long id : tagIds) {
            t.add(id);
        }
        body.put("action", action);
        return objectMapper.writeValueAsString(body);
    }

    private boolean hasTag(JsonNode question, long tagId) {
        for (JsonNode tag : question.get("tags")) {
            if (tag.get("id").asLong() == tagId) {
                return true;
            }
        }
        return false;
    }

    private boolean succeededContains(JsonNode data, long id) {
        for (JsonNode item : data.get("succeeded")) {
            if (item.asLong() == id) {
                return true;
            }
        }
        return false;
    }

    private String reasonOf(JsonNode data, long id) {
        Map<Long, String> reasons = new HashMap<>();
        for (JsonNode item : data.get("failed")) {
            reasons.put(item.get("id").asLong(), item.get("reason").asText());
        }
        return reasons.get(id);
    }

    @Test
    void bindInsertsOnlyMissingPairsIdempotentlyAndKeepsExistingTags() throws Exception {
        String teacher = registerTeacher();
        long tagA = createTag(teacher, unique("批量标签A"), "SUBJECT");
        long tagB = createTag(teacher, unique("批量标签B"), "SUBJECT");
        long tagC = createTag(teacher, unique("批量标签C"), "CUSTOM");
        long q1 = createQuestion(teacher, 1, "批量打标单选一", "A", List.of("甲", "乙"), List.of(tagA));
        long q2 = createQuestion(teacher, 3, "批量打标判断二", "对", null, null);

        // BIND 追加 tagB、tagC：q1 已有 tagA 保留，q2 此前无标签
        JsonNode data = perform(jsonPost("/api/questions/batch-tags", teacher,
                batchTagsBody("BIND", new long[]{q1, q2}, tagB, tagC)), 200).get("data");
        assertEquals(2, data.get("succeeded").size());
        assertEquals(0, data.get("failed").size());

        JsonNode q1Detail = perform(jsonGet("/api/questions/" + q1, teacher), 200).get("data");
        assertTrue(hasTag(q1Detail, tagA), "BIND 后原标签 tagA 应保留");
        assertTrue(hasTag(q1Detail, tagB));
        assertTrue(hasTag(q1Detail, tagC));

        // 重复 BIND 幂等：再追加同一批标签，关联行不重复，标签集合不变
        perform(jsonPost("/api/questions/batch-tags", teacher,
                batchTagsBody("BIND", new long[]{q1}, tagB, tagC)), 200);
        q1Detail = perform(jsonGet("/api/questions/" + q1, teacher), 200).get("data");
        assertEquals(3, q1Detail.get("tags").size(), "幂等追加不得产生重复关联行");
        assertTrue(hasTag(q1Detail, tagA));
        assertTrue(hasTag(q1Detail, tagB));
        assertTrue(hasTag(q1Detail, tagC));
    }

    @Test
    void unbindRemovesOnlySelectedLinksAndKeepsOthers() throws Exception {
        String teacher = registerTeacher();
        long tagA = createTag(teacher, unique("解绑标签A"), "SUBJECT");
        long tagB = createTag(teacher, unique("解绑标签B"), "SUBJECT");
        long q1 = createQuestion(teacher, 1, "解绑单选一", "A", List.of("甲", "乙"),
                List.of(tagA, tagB));

        // 解绑 tagA：仅删除 tagA 关联，tagB 保留
        JsonNode data = perform(jsonPost("/api/questions/batch-tags", teacher,
                batchTagsBody("UNBIND", new long[]{q1}, tagA)), 200).get("data");
        assertEquals(1, data.get("succeeded").size());
        assertEquals(0, data.get("failed").size());

        JsonNode q1Detail = perform(jsonGet("/api/questions/" + q1, teacher), 200).get("data");
        assertTrue(!hasTag(q1Detail, tagA), "UNBIND 后 tagA 关联应被移除");
        assertTrue(hasTag(q1Detail, tagB), "UNBIND 非选标签 tagB 应保留");
    }

    @Test
    void ownershipViolationGoesToFailedWhileAdminIsAllowed() throws Exception {
        String teacherA = registerTeacher();
        String teacherB = registerTeacher();
        long tagA1 = createTag(teacherA, unique("越权标签A"), "SUBJECT");
        long qA = createQuestion(teacherA, 1, "教师A的题", "A", List.of("甲", "乙"), null);
        long qB = createQuestion(teacherB, 2, "教师B的题", "B", List.of("甲", "乙", "丙"), null);

        // 教师 A 试图给教师 B 的题打标：越权题目计入 failed，教师 A 的题照常处理
        JsonNode data = perform(jsonPost("/api/questions/batch-tags", teacherA,
                batchTagsBody("BIND", new long[]{qA, qB}, tagA1)), 200).get("data");
        assertTrue(succeededContains(data, qA));
        assertEquals(REASON_FORBIDDEN, reasonOf(data, qB));
        assertTrue(!succeededContains(data, qB));

        // 教师 B 的题未被教师 A 改动
        JsonNode qBDetail = perform(jsonGet("/api/questions/" + qB, teacherB), 200).get("data");
        assertEquals(0, qBDetail.get("tags").size());

        // ADMIN 越级放行：可将 tagA1 打给教师 B 的题
        String admin = loginToken("admin", "admin123");
        JsonNode adminData = perform(jsonPost("/api/questions/batch-tags", admin,
                batchTagsBody("BIND", new long[]{qB}, tagA1)), 200).get("data");
        assertEquals(1, adminData.get("succeeded").size());
        qBDetail = perform(jsonGet("/api/questions/" + qB, teacherB), 200).get("data");
        assertTrue(hasTag(qBDetail, tagA1));
    }

    @Test
    void missingQuestionGoesToFailedWithoutBlockingOthers() throws Exception {
        String teacher = registerTeacher();
        long tagA1 = createTag(teacher, unique("缺题标签A"), "SUBJECT");
        long q1 = createQuestion(teacher, 1, "缺题单选", "A", List.of("甲", "乙"), null);

        JsonNode data = perform(jsonPost("/api/questions/batch-tags", teacher,
                batchTagsBody("BIND", new long[]{q1, 999_999_999L}, tagA1)), 200).get("data");
        assertTrue(succeededContains(data, q1));
        assertEquals(REASON_NOT_FOUND, reasonOf(data, 999_999_999L));
    }

    @Test
    void rejectsEmptyListsAndDuplicateQuestionsAndIllegalAction() throws Exception {
        String teacher = registerTeacher();
        long tagA1 = createTag(teacher, unique("校验标签A"), "SUBJECT");
        long q1 = createQuestion(teacher, 1, "校验单选", "A", List.of("甲", "乙"), null);

        // 空题目列表 → 400
        perform(jsonPost("/api/questions/batch-tags", teacher,
                batchTagsBody("BIND", new long[]{}, tagA1)), 400);
        // 空标签列表 → 400
        perform(jsonPost("/api/questions/batch-tags", teacher,
                batchTagsBody("BIND", new long[]{q1})), 400);
        // 请求内重复题目 → 400（与批量删除/入卷口径一致）
        JsonNode dup = perform(jsonPost("/api/questions/batch-tags", teacher,
                batchTagsBody("BIND", new long[]{q1, q1}, tagA1)), 400);
        assertEquals("请求内存在重复题目", dup.get("message").asText());
        // 非法 action → 400
        perform(jsonPost("/api/questions/batch-tags", teacher,
                batchTagsBody("FOO", new long[]{q1}, tagA1)), 400);
        // 部分标签不存在 → 400
        JsonNode badTag = perform(jsonPost("/api/questions/batch-tags", teacher,
                batchTagsBody("BIND", new long[]{q1}, 999_999_998L, tagA1)), 400);
        assertEquals("部分标签不存在或已删除", badTag.get("message").asText());

        // 以上均未改动 q1 标签
        JsonNode q1Detail = perform(jsonGet("/api/questions/" + q1, teacher), 200).get("data");
        assertEquals(0, q1Detail.get("tags").size());
    }

    @Test
    void removedTagLinkVisibleAfterListRefresh() throws Exception {
        String teacher = registerTeacher();
        long tagA1 = createTag(teacher, unique("刷新标签A"), "SUBJECT");
        long tagB1 = createTag(teacher, unique("刷新标签B"), "SUBJECT");
        long q1 = createQuestion(teacher, 1, "刷新单选", "A", List.of("甲", "乙"), null);

        perform(jsonPost("/api/questions/batch-tags", teacher,
                batchTagsBody("BIND", new long[]{q1}, tagA1, tagB1)), 200);
        perform(jsonPost("/api/questions/batch-tags", teacher,
                batchTagsBody("UNBIND", new long[]{q1}, tagA1)), 200);

        // 列表口径（page 装配标签）反映最新标签
        JsonNode page = perform(jsonGet("/api/questions?type=1&size=10", teacher), 200).get("data");
        JsonNode row = null;
        for (JsonNode item : page.get("list")) {
            if (item.get("id").asLong() == q1) {
                row = item;
                break;
            }
        }
        assertTrue(row != null);
        assertEquals(1, row.get("tags").size());
        assertEquals(tagB1, row.get("tags").get(0).get("id").asLong());
    }
}