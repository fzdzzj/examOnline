package com.exam.paper;

import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试卷模板与克隆复用集成测试（spec-delta add-paper-template-clone · question-bank 域）：
 * clone 全量复制与草稿产物、克隆锁定卷得可编辑草稿且原卷快照零影响、非归属 403、
 * 另存为模板原卷保留、模板嵌套拒绝、模板选考试卷拒绝（不产生 exam 行）、
 * 模板快照拒绝、默认视图零漂移与模板视图、从模板建卷。
 *
 * <p>建表只靠 schema.sql（禁 @Sql，见 AGENTS.md 硬约定 2）。
 */
class PaperTemplateCloneIntegrationTest extends IntegrationTestBase {

    private static final String SUFFIX = "（副本）";

    /** Scenario: 克隆全量复制与草稿产物。 */
    @Test
    void cloneCopiesAllAndProducesDraft() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, unique("克隆单选"), "A", List.of("甲", "乙"), null);
        long q2 = createQuestion(teacher, 3, unique("克隆判断"), "T", null, null);
        long source = createPaper(teacher, unique("克隆原卷"), 15.0);
        perform(jsonPost("/api/papers/" + source + "/questions", teacher,
                "{\"questionId\":" + q1 + "}"), 200);
        perform(jsonPost("/api/papers/" + source + "/questions", teacher,
                "{\"questionId\":" + q2 + ",\"score\":10}"), 200);
        // 调序：q2 排第 1（检验顺序全量复制）
        perform(jsonPut("/api/papers/" + source + "/questions/order", teacher,
                "{\"questionIds\":[" + q2 + "," + q1 + "]}"), 200);

        JsonNode sourceDetail = detail(source, teacher);
        JsonNode copy = perform(jsonPost("/api/papers/" + source + "/clone", teacher, null), 200)
                .get("data");

        long copyId = copy.get("id").asLong();
        assertNotEquals(source, copyId);
        assertEquals(sourceDetail.get("title").asText() + SUFFIX, copy.get("title").asText());
        assertEquals(0, copy.get("status").asInt(), "克隆产物必须为草稿态");
        assertTrue(isNullOrMissing(copy, "snapshotId"), "克隆产物无快照");
        assertEquals(2, copy.get("questionCount").asInt());
        assertEquals(sourceDetail.get("createdBy").asLong(), copy.get("createdBy").asLong(),
                "新卷 owner=操作者");

        // 题目集合/顺序/分值覆盖与源卷逐项一致
        JsonNode copyDetail = detail(copyId, teacher);
        assertEquals(questionSignatures(sourceDetail), questionSignatures(copyDetail));
        // 源卷内容零变化
        assertEquals(sourceDetail, detail(source, teacher));
    }

    /** Scenario: 克隆锁定卷得可编辑草稿（源卷锁定态与快照零影响）。 */
    @Test
    void cloneLockedPaperYieldsEditableDraft() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, unique("锁卷单选"), "A", List.of("甲", "乙"), null);
        long source = createPaper(teacher, unique("锁定源卷"), 5.0);
        perform(jsonPost("/api/papers/" + source + "/questions", teacher,
                "{\"questionId\":" + q1 + "}"), 200);
        perform(jsonPost("/api/papers/" + source + "/snapshot", teacher, null), 200);
        JsonNode sourceSnapBefore = perform(jsonGet("/api/papers/" + source + "/snapshot", teacher), 200)
                .get("data");

        JsonNode copy = perform(jsonPost("/api/papers/" + source + "/clone", teacher, null), 200)
                .get("data");
        long copyId = copy.get("id").asLong();
        assertEquals(0, copy.get("status").asInt());
        assertTrue(isNullOrMissing(copy, "snapshotId"));
        assertEquals(1, copy.get("questionCount").asInt());

        // 产物可编辑（未锁定）：改标题成功
        perform(jsonPut("/api/papers/" + copyId, teacher, "{\"title\":\"" + unique("改名") + "\"}"), 200);

        // 源卷保持锁定态且快照内容零变化
        JsonNode sourceAfter = detail(source, teacher);
        assertEquals(1, sourceAfter.get("status").asInt());
        assertFalse(isNullOrMissing(sourceAfter, "snapshotId"));
        assertEquals(sourceSnapBefore, perform(jsonGet("/api/papers/" + source + "/snapshot", teacher), 200)
                .get("data"));
    }

    /** Scenario: 非归属拒绝（clone 与 save-as-template 均 403，且不产生任何复制）。 */
    @Test
    void nonOwnerRejected() throws Exception {
        String teacherA = registerTeacher();
        String teacherB = registerTeacher();
        long paper = createPaper(teacherA, unique("越权卷"), 0);

        perform(jsonPost("/api/papers/" + paper + "/clone", teacherB, null), 403);
        perform(jsonPost("/api/papers/" + paper + "/save-as-template", teacherB, null), 403);

        JsonNode bPapers = pageList(teacherB, false);
        assertFalse(containsId(bPapers, paper));
        assertEquals(0, bPapers.size(), "非归属教师不产生任何复制（普通视图）");
        assertEquals(0, pageList(teacherB, true).size(), "非归属教师不产生任何复制（模板视图）");
    }

    /** Scenario: 另存为模板原卷保留（源卷锁定态，模板入库且草稿/无快照）。 */
    @Test
    void saveAsTemplateKeepsSource() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, unique("模板单选"), "A", List.of("甲", "乙"), null);
        long source = createPaper(teacher, unique("模板原卷"), 5.0);
        perform(jsonPost("/api/papers/" + source + "/questions", teacher,
                "{\"questionId\":" + q1 + "}"), 200);
        // 源卷任意状态：先生成快照锁定
        perform(jsonPost("/api/papers/" + source + "/snapshot", teacher, null), 200);
        JsonNode sourceBefore = detail(source, teacher);
        JsonNode sourceSnapBefore = perform(jsonGet("/api/papers/" + source + "/snapshot", teacher), 200)
                .get("data");

        JsonNode tpl = perform(jsonPost("/api/papers/" + source + "/save-as-template", teacher, null), 200)
                .get("data");
        long tplId = tpl.get("id").asLong();
        assertEquals(sourceBefore.get("title").asText() + SUFFIX, tpl.get("title").asText());
        assertEquals(0, tpl.get("status").asInt());
        assertTrue(isNullOrMissing(tpl, "snapshotId"));
        assertEquals(1, tpl.get("questionCount").asInt());

        // 模板库视图可见、默认视图不可见
        assertTrue(containsId(pageList(teacher, true), tplId), "模板应出现在模板库视图");
        assertFalse(containsId(pageList(teacher, false), tplId), "模板不应出现在默认视图");
        // 原卷及其快照零变化
        assertEquals(sourceBefore, detail(source, teacher));
        assertEquals(sourceSnapBefore, perform(jsonGet("/api/papers/" + source + "/snapshot", teacher), 200)
                .get("data"));
    }

    /** Scenario: 模板不可再另存为模板。 */
    @Test
    void templateCannotBeSavedAsTemplate() throws Exception {
        String teacher = registerTeacher();
        long source = createPaper(teacher, unique("嵌套原卷"), 0);
        long tplId = makeTemplate(source, teacher);
        int before = pageList(teacher, true).size();

        perform(jsonPost("/api/papers/" + tplId + "/save-as-template", teacher, null), 400);

        assertEquals(before, pageList(teacher, true).size(), "模板嵌套不产生新模板");
    }

    /** Scenario: 模板不可被选为考试卷（不产生 exam 行）。 */
    @Test
    void templateCannotBeExamPaper() throws Exception {
        String teacher = registerTeacher();
        long source = createPaper(teacher, unique("拒绝考试原卷"), 0);
        long tplId = makeTemplate(source, teacher);

        assertEquals(0, perform(jsonGet("/api/exams", teacher), 200).get("data").size());

        LocalDateTime start = LocalDateTime.now().plusHours(1);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", unique("考试"));
        body.put("paperId", tplId);
        body.put("startTime", start.toString());
        body.put("endTime", start.plusHours(1).toString());
        body.put("durationMinutes", 60);
        perform(jsonPost("/api/exams", teacher, objectMapper.writeValueAsString(body)), 400);

        assertEquals(0, perform(jsonGet("/api/exams", teacher), 200).get("data").size(),
                "模板卷被拒后不得产生 exam 行");
    }

    /** Scenario: 模板不可生成快照（papers.status 与 snapshotId 零变化）。 */
    @Test
    void templateCannotGenerateSnapshot() throws Exception {
        String teacher = registerTeacher();
        long source = createPaper(teacher, unique("拒绝快照原卷"), 0);
        long tplId = makeTemplate(source, teacher);

        JsonNode before = detail(tplId, teacher);
        perform(jsonPost("/api/papers/" + tplId + "/snapshot", teacher, null), 400);
        JsonNode after = detail(tplId, teacher);

        assertEquals(before.get("status").asInt(), after.get("status").asInt());
        assertTrue(isNullOrMissing(after, "snapshotId"));
    }

    /** Scenario: 默认视图零漂移与模板视图。 */
    @Test
    void defaultViewZeroDriftAndTemplateView() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, unique("视图单选"), "A", List.of("甲", "乙"), null);
        long normal = createPaper(teacher, unique("普通卷"), 5.0);
        perform(jsonPost("/api/papers/" + normal + "/questions", teacher,
                "{\"questionId\":" + q1 + "}"), 200);
        long tplId = makeTemplate(normal, teacher);

        JsonNode def = pageList(teacher, false);
        assertTrue(containsId(def, normal), "默认视图含普通试卷");
        assertFalse(containsId(def, tplId), "默认视图不含模板卷");
        JsonNode tplView = pageList(teacher, true);
        assertTrue(containsId(tplView, tplId), "模板视图含模板卷");
        assertFalse(containsId(tplView, normal), "模板视图不含普通卷");
    }

    /** 从模板一键建卷：clone 模板得普通草稿卷，模板本身不动。 */
    @Test
    void cloneFromTemplateBuildsEditableDraft() throws Exception {
        String teacher = registerTeacher();
        long q1 = createQuestion(teacher, 1, unique("建卷单选"), "A", List.of("甲", "乙"), null);
        long source = createPaper(teacher, unique("建卷原卷"), 5.0);
        perform(jsonPost("/api/papers/" + source + "/questions", teacher,
                "{\"questionId\":" + q1 + "}"), 200);
        long tplId = makeTemplate(source, teacher);
        JsonNode tplBefore = detail(tplId, teacher);

        JsonNode built = perform(jsonPost("/api/papers/" + tplId + "/clone", teacher, null), 200)
                .get("data");
        long builtId = built.get("id").asLong();
        assertEquals(0, built.get("status").asInt(), "从模板建卷产物为草稿");
        assertTrue(isNullOrMissing(built, "snapshotId"));
        assertEquals(1, built.get("questionCount").asInt());
        assertEquals(tplBefore.get("title").asText() + SUFFIX, built.get("title").asText());
        assertEquals(questionSignatures(tplBefore), questionSignatures(detail(builtId, teacher)));

        // 产物是普通试卷（进默认视图、不进模板库），模板本身零变化
        assertTrue(containsId(pageList(teacher, false), builtId));
        assertFalse(containsId(pageList(teacher, true), builtId));
        assertEquals(tplBefore, detail(tplId, teacher));
    }

    // ==================== 辅助 ====================

    private long makeTemplate(long sourcePaperId, String token) throws Exception {
        return perform(jsonPost("/api/papers/" + sourcePaperId + "/save-as-template", token, null), 200)
                .get("data").get("id").asLong();
    }

    private JsonNode detail(long paperId, String token) throws Exception {
        return perform(jsonGet("/api/papers/" + paperId, token), 200).get("data");
    }

    private JsonNode pageList(String token, boolean template) throws Exception {
        String url = "/api/papers?page=1&size=100" + (template ? "&isTemplate=true" : "");
        return perform(jsonGet(url, token), 200).get("data");
    }

    private static boolean containsId(JsonNode list, long id) {
        for (JsonNode item : list) {
            if (item.get("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }

    /** 响应序列化省略 null 字段：字段缺失与显式 null 等价。 */
    private static boolean isNullOrMissing(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull();
    }

    /** 题目签名（题号:题目ID:分值），用于逐项比对复制结果。 */
    private static List<String> questionSignatures(JsonNode detail) {
        List<String> sigs = new ArrayList<>();
        for (JsonNode q : detail.get("questions")) {
            sigs.add(q.get("number").asInt() + ":" + q.get("questionId").asLong()
                    + ":" + q.get("score").asDouble());
        }
        return sigs;
    }
}
