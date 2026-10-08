package com.exam.exam;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 考试列表服务端筛选集成测试（add-exam-list-filtering，U-3）：
 * 验证 GET /api/exams 端点对 title（模糊匹配）、status（精确匹配）的动态条件筛选，
 * 空格 blank 视为不过滤、组合筛选、缺省全量分页回归、以及教师所有权隔离保持。
 */
@DisplayName("考试列表服务端筛选与分页集成测试")
class ExamListFilteringIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ExamMapper examMapper;

    private long createExamWithTitle(String token, String title, long paperId) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", title);
        body.put("paperId", paperId);
        body.put("startTime", LocalDateTime.now().plusHours(1).toString());
        body.put("endTime", LocalDateTime.now().plusHours(2).toString());
        body.put("durationMinutes", 60);
        JsonNode data = perform(jsonPost("/api/exams", token, objectMapper.writeValueAsString(body)), 200)
                .get("data");
        return data.get("id").asLong();
    }

    private void updateExamStatus(long examId, int status) {
        examMapper.update(null, Wrappers.<Exam>lambdaUpdate()
                .eq(Exam::getId, examId)
                .set(Exam::getStatus, status));
    }

    @Test
    @DisplayName("用例①：按标题关键词模糊匹配（命中按 id DESC 排序，不匹配返回空列表）")
    void titleSearchMatchAndMismatch() throws Exception {
        String teacher = registerTeacher();
        long paperId = createPaper(teacher, "物理考卷", 100.0);

        long e1 = createExamWithTitle(teacher, "2026春期中物理测试", paperId);
        long e2 = createExamWithTitle(teacher, "2026春期末物理测试", paperId);
        long e3 = createExamWithTitle(teacher, "2026春英语听力测试", paperId);

        // 搜索 "物理"：应命中 e2, e1（按 id 倒序），不含 e3
        JsonNode dataMatch = perform(jsonGet("/api/exams", teacher).param("title", "物理"), 200).get("data");
        assertEquals(2, dataMatch.size(), "搜索'物理'应命中 2 条记录");
        assertEquals(e2, dataMatch.get(0).get("id").asLong(), "第一条应为较高 id 的 e2");
        assertEquals(e1, dataMatch.get(1).get("id").asLong(), "第二条应为较低 id 的 e1");
        assertTrue(dataMatch.get(0).get("title").asText().contains("物理"));
        assertTrue(dataMatch.get(1).get("title").asText().contains("物理"));

        // 搜索 "数学"：应返回空列表
        JsonNode dataNoMatch = perform(jsonGet("/api/exams", teacher).param("title", "数学"), 200).get("data");
        assertEquals(0, dataNoMatch.size(), "搜索不匹配关键词应返回空列表");
    }

    @Test
    @DisplayName("用例②：纯空格 blank 视为不过滤，返回该教师全量分页考试")
    void blankTitleIsTreatedAsNoFilter() throws Exception {
        String teacher = registerTeacher();
        long paperId = createPaper(teacher, "通用考卷", 100.0);

        long e1 = createExamWithTitle(teacher, "语文考试A", paperId);
        long e2 = createExamWithTitle(teacher, "数学考试B", paperId);

        // 传入纯空格
        JsonNode dataBlank = perform(jsonGet("/api/exams", teacher).param("title", "   "), 200).get("data");
        assertTrue(dataBlank.size() >= 2, "纯空格应不过滤，至少返回创建的 2 条记录");

        List<Long> ids = new ArrayList<>();
        dataBlank.forEach(item -> ids.add(item.get("id").asLong()));
        assertTrue(ids.contains(e1), "应包含 e1");
        assertTrue(ids.contains(e2), "应包含 e2");
    }

    @Test
    @DisplayName("用例③：按状态精确筛选仅返回对应状态的考试")
    void exactStatusFilter() throws Exception {
        String teacher = registerTeacher();
        long paperId = createPaper(teacher, "状态测试卷", 100.0);

        long e1 = createExamWithTitle(teacher, "未开始考试", paperId); // status 0
        long e2 = createExamWithTitle(teacher, "进行中考试", paperId);
        updateExamStatus(e2, 1); // status 1
        long e3 = createExamWithTitle(teacher, "已结束考试", paperId);
        updateExamStatus(e3, 2); // status 2

        // status=0
        JsonNode dataStatus0 = perform(jsonGet("/api/exams", teacher).param("status", "0"), 200).get("data");
        assertFalse(dataStatus0.isEmpty(), "status=0 结果非空");
        for (JsonNode item : dataStatus0) {
            assertEquals(0, item.get("status").asInt(), "每条记录状态必须为 0");
        }
        List<Long> ids0 = new ArrayList<>();
        dataStatus0.forEach(item -> ids0.add(item.get("id").asLong()));
        assertTrue(ids0.contains(e1), "status=0 应包含 e1");
        assertFalse(ids0.contains(e2), "status=0 不应包含 e2");
        assertFalse(ids0.contains(e3), "status=0 不应包含 e3");

        // status=1
        JsonNode dataStatus1 = perform(jsonGet("/api/exams", teacher).param("status", "1"), 200).get("data");
        for (JsonNode item : dataStatus1) {
            assertEquals(1, item.get("status").asInt(), "每条记录状态必须为 1");
        }
        List<Long> ids1 = new ArrayList<>();
        dataStatus1.forEach(item -> ids1.add(item.get("id").asLong()));
        assertTrue(ids1.contains(e2), "status=1 应包含 e2");
        assertFalse(ids1.contains(e1), "status=1 不应包含 e1");
    }

    @Test
    @DisplayName("用例④：标题与状态组合筛选")
    void combinedTitleAndStatusFilter() throws Exception {
        String teacher = registerTeacher();
        long paperId = createPaper(teacher, "计网试卷", 100.0);

        long e1 = createExamWithTitle(teacher, "计算机网络期中", paperId); // 0
        long e2 = createExamWithTitle(teacher, "计算机网络期末", paperId);
        updateExamStatus(e2, 2); // 2 已结束
        long e3 = createExamWithTitle(teacher, "数据结构期中", paperId); // 0

        // 筛选 title="计算机网络" AND status=0
        JsonNode data = perform(jsonGet("/api/exams", teacher).param("title", "计算机网络").param("status", "0"), 200).get("data");
        assertEquals(1, data.size(), "组合筛选应仅命中 1 条记录");
        assertEquals(e1, data.get(0).get("id").asLong(), "命中的必须是 e1");
        assertEquals(0, data.get(0).get("status").asInt());
        assertTrue(data.get(0).get("title").asText().contains("计算机网络"));
    }

    @Test
    @DisplayName("用例⑤：不传参全量分页与现状回归（id DESC 稳定排序）")
    void unfilteredDefaultReturnsAllWithRegression() throws Exception {
        String teacher = registerTeacher();
        long paperId = createPaper(teacher, "回归测试卷", 100.0);

        long e1 = createExamWithTitle(teacher, "回归考试1", paperId);
        long e2 = createExamWithTitle(teacher, "回归考试2", paperId);

        JsonNode data = perform(jsonGet("/api/exams", teacher), 200).get("data");
        assertTrue(data.size() >= 2, "不传参应返回全量");
        List<Long> ids = new ArrayList<>();
        data.forEach(item -> ids.add(item.get("id").asLong()));
        int idx2 = ids.indexOf(e2);
        int idx1 = ids.indexOf(e1);
        assertTrue(idx2 >= 0 && idx1 >= 0, "应同时包含 e1 与 e2");
        assertTrue(idx2 < idx1, "e2 (更高 id) 应排在 e1 之前，保持 id DESC");
    }

    @Test
    @DisplayName("用例⑥：维持教师所有权隔离（非管理员仅查本人考试，不跨教师越权）")
    void teacherOwnershipIsolationPreserved() throws Exception {
        String teacher1 = registerTeacher();
        String teacher2 = registerTeacher();

        long p1 = createPaper(teacher1, "教师1试卷", 100.0);
        long p2 = createPaper(teacher2, "教师2试卷", 100.0);

        long e1 = createExamWithTitle(teacher1, "深度学习研讨A", p1);
        long e2 = createExamWithTitle(teacher2, "深度学习研讨B", p2);

        // teacher1 搜索 "深度学习"：只能查到 e1，不能查到 e2
        JsonNode data = perform(jsonGet("/api/exams", teacher1).param("title", "深度学习"), 200).get("data");
        List<Long> ids = new ArrayList<>();
        data.forEach(item -> ids.add(item.get("id").asLong()));

        assertTrue(ids.contains(e1), "教师 1 应该查到自己的考试 e1");
        assertFalse(ids.contains(e2), "教师 1 绝不应该查到教师 2 的考试 e2");
    }
}
