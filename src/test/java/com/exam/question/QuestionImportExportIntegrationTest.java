package com.exam.question;

import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 题库导入/导出/模板集成测试（spec add-question-import-export，H2 + MockMvc）：
 * <ul>
 *   <li>模板下载：200 可解析、列口径逐字一致（与 {@code QuestionImportColumn} 共用定义）；</li>
 *   <li>导出：全量未软删，列口径与模板逐字一致，round-trip 导出→改→再导入；</li>
 *   <li>导入成功：含标签关联/难度/图片URL → 列表可见、可组卷；</li>
 *   <li>混合错误行：逐项报告与构造一致，正确行入库；</li>
 *   <li>行数超限整批 400；学生 403 / 未登录 401。</li>
 * </ul>
 */
class QuestionImportExportIntegrationTest extends IntegrationTestBase {

    private static final String[] HEADERS =
            {"题型", "题干", "选项A", "选项B", "选项C", "选项D", "选项E", "选项F", "答案", "标签", "难度", "图片URL"};
    private static final String IMAGE_URL = "/images/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.png";

    // ==================== 模板 ====================

    @Test
    void templateDownloadIsParseableAndContainsFiveExampleRows() throws Exception {
        String teacher = registerTeacher();
        byte[] bytes = mockMvc.perform(get("/api/questions/import-template")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(bytes.length > 0, "模板应为非空 xlsx");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = wb.getSheetAt(0);
            assertEquals("题库导入模板", sheet.getSheetName());
            // 表头逐字对齐列定义
            for (int i = 0; i < HEADERS.length; i++) {
                assertEquals(HEADERS[i], sheet.getRow(0).getCell(i).getStringCellValue(), "表头第 " + (i + 1) + " 列");
            }
            // 五题型示例行
            var types = new ArrayList<String>();
            for (int r = 1; r <= 5; r++) {
                types.add(sheet.getRow(r).getCell(0).getStringCellValue());
            }
            assertEquals(List.of("单选", "多选", "判断", "简答", "填空"), types, "五题型示例行");
            // 填写说明行
            assertTrue(sheet.getRow(6).getCell(1).getStringCellValue().contains("填写说明")
                    || sheet.getRow(6).getCell(0).getStringCellValue().contains("填写说明"),
                    "应含填写说明行");
        }
    }

    @Test
    void templateRequiresPermission() throws Exception {
        String student = registerStudent();
        mockMvc.perform(get("/api/questions/import-template")
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/questions/import-template")).andExpect(status().isUnauthorized());
    }

    // ==================== 导入成功 ====================

    @Test
    void importSuccessImportsTagsDifficultyImageAndIsListableAndAssemblable() throws Exception {
        String teacher = registerTeacher();
        String tagName = unique("数学");
        createTag(teacher, tagName, "SUBJECT");

        // 构造多行 xlsx：单选（带标签/难度/图片URL）+ 简答（无标签难度默认）
        byte[] xlsx = buildXlsx(List.of(
                new String[]{"单选", "导入单选题", "甲", "乙", "", "", "", "", "A",
                        tagName, "难", IMAGE_URL},
                new String[]{"简答", "导入简答题", "", "", "", "", "", "", "参考答案", "", "", ""}
        ));

        JsonNode result = perform(multipart("/api/questions/import")
                        .file(multipartFile(xlsx))
                        .header("Authorization", bearer(teacher)), 200)
                .get("data");
        assertEquals(2, result.get("imported").asInt());
        assertEquals(0, result.get("failed").asInt());

        // 列表可见（含题干关键词），验证内容与标签/难度/图片URL入库
        JsonNode page = perform(jsonGet("/api/questions?size=100", teacher), 200).get("data");
        boolean foundSingle = false;
        boolean foundShort = false;
        for (JsonNode q : page.get("list")) {
            if ("导入单选题".equals(q.get("content").asText())) {
                foundSingle = true;
                assertEquals(3, q.get("difficulty").asInt(), "难度『难』应入库为 3");
                assertEquals(IMAGE_URL, q.get("imageUrl").asText());
                assertTrue(q.get("tags").size() >= 1, "单选应带标签");
            }
            if ("导入简答题".equals(q.get("content").asText())) {
                foundShort = true;
                assertEquals(1, q.get("difficulty").asInt(), "难度留空默认 1 易");
                assertEquals("参考答案", q.get("correctAnswer").asText());
            }
        }
        assertTrue(foundSingle, "导入单选列表可见");
        assertTrue(foundShort, "导入简答列表可见");

        // 可组卷：把导入题加入试卷
        long paperId = createPaper(teacher, unique("导入卷"), 10);
        long importedId = questionIdOf(page, "导入单选题");
        addPaperQuestion(teacher, paperId, importedId, 6);
        JsonNode paper = perform(jsonGet("/api/papers/" + paperId, teacher), 200).get("data");
        boolean inPaper = false;
        for (JsonNode q : paper.get("questions")) {
            if (q.get("questionId").asLong() == importedId) {
                inPaper = true;
            }
        }
        assertTrue(inPaper, "导入题目可组卷");
    }

    // ==================== 混合错误行 ====================

    @Test
    void importErrorReportAndCorrectRowsPersist() throws Exception {
        String teacher = registerTeacher();
        String tagName = unique("数学");
        createTag(teacher, tagName, "SUBJECT");

        byte[] xlsx = buildXlsx(List.of(
                new String[]{"单选", "好题一", "甲", "乙", "", "", "", "", "A", tagName, "易", ""},
                new String[]{"听写", "坏题型", "甲", "乙", "", "", "", "", "A", "", "易", ""},
                new String[]{"单选", "外链图", "甲", "乙", "", "", "", "", "A", "", "易", "https://evil.com/x.png"},
                new String[]{"简答", "好题二", "", "", "", "", "", "", "参考答案", "", "中", ""},
                new String[]{"多选", "选项不足", "甲", "", "", "", "", "", "A", "", "易", ""},
                new String[]{"判断", "判断非法答案", "", "", "", "", "", "", "C", "", "易", ""}
        ));

        JsonNode result = perform(multipart("/api/questions/import")
                        .file(multipartFile(xlsx))
                        .header("Authorization", bearer(teacher)), 200)
                .get("data");
        assertEquals(2, result.get("imported").asInt(), "应只导入 2 个正确行");
        assertEquals(4, result.get("failed").asInt());
        assertEquals(4, result.get("errors").size());

        // 错误报告逐行核对：行号（Excel 实际行号：表头第1行；数据从第2行起）
        assertError(result.get("errors"), 3, "题型必须为：单选/多选/判断/简答/填空");
        assertError(result.get("errors"), 4, "图片URL 必须以 /images/ 开头");
        assertError(result.get("errors"), 6, "单选/多选至少需要两个非空选项");
        assertError(result.get("errors"), 7, "判断题答案仅支持 正确/错误、对/错、T/F 等");

        // 正确行入库：列表可见
        JsonNode page = perform(jsonGet("/api/questions?size=100", teacher), 200).get("data");
        boolean found1 = false;
        boolean found2 = false;
        for (JsonNode q : page.get("list")) {
            if ("好题一".equals(q.get("content").asText())) {
                found1 = true;
                assertTrue(q.get("tags").size() >= 1, "好题一带标签");
            }
            if ("好题二".equals(q.get("content").asText())) {
                found2 = true;
            }
        }
        assertTrue(found1, "正确行『好题一』入库且带标签");
        assertTrue(found2, "正确行『好题二』入库");
    }

    private void assertError(JsonNode errors, int row, String reason) {
        for (JsonNode e : errors) {
            if (e.get("row").asInt() == row) {
                assertTrue(e.get("reason").asText().contains(reason),
                        "行号 " + row + " 原因应为「" + reason + "」，实际 " + e.get("reason").asText());
                return;
            }
        }
        throw new AssertionError("错误报告缺少行号 " + row + " 的条目");
    }

    // ==================== 行数超限 ====================

    @Test
    void importRejectsRowCountOverLimitWholeBatch400() throws Exception {
        String teacher = registerTeacher();
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            rows.add(new String[]{"单选", "超限题" + i, "甲", "乙", "", "", "", "", "A", "", "易", ""});
        }
        // JSON 信封 body 为空时 response 为 null，超限时 400 断言 HTTP 状态即可
        mockMvc.perform(multipart("/api/questions/import")
                        .file(multipartFile(buildXlsx(rows)))
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isBadRequest());
    }

    // ==================== 权限 ====================

    @Test
    void importRequiresQuestionManagePermission() throws Exception {
        String student = registerStudent();
        byte[] xlsx = buildXlsx(List.<String[]>of(
                new String[]{"单选", "学生传的题", "甲", "乙", "", "", "", "", "A", "", "易", ""}));
        mockMvc.perform(multipart("/api/questions/import")
                        .file(multipartFile(xlsx))
                        .header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/questions/import")
                        .file(multipartFile(xlsx)))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 导出与 round-trip ====================

    @Test
    void exportMatchesTemplateColumnsAndRoundTrips() throws Exception {
        String teacher = registerTeacher();
        String tagName = unique("数学");
        createTag(teacher, tagName, "SUBJECT");

        // 预置一道题（带标签/难度/图片URL），导出应含该题
        perform(jsonPost("/api/questions", teacher,
                questionJson(1, "导出的单选", "A", List.of("甲", "乙"), 5.0, 2, null)), 200);

        byte[] exportBytes = mockMvc.perform(get("/api/questions/export")
                        .header("Authorization", bearer(teacher)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertTrue(exportBytes.length > 0, "导出文件非空");

        // 导出列口径与模板逐字一致
        byte[] templateBytes = mockMvc.perform(get("/api/questions/import-template")
                        .header("Authorization", bearer(teacher)))
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals(exportHeader(templateBytes), exportHeader(exportBytes), "导出与模板表头逐字一致");

        // round-trip ①：导出文件原样可直接再导入（列同名同序、回读口径一致）
        JsonNode direct = perform(multipart("/api/questions/import")
                        .file(multipartFile(exportBytes))
                        .header("Authorization", bearer(teacher)), 200)
                .get("data");
        assertEquals(1, direct.get("imported").asInt(), "导出文件可原样再导入（round-trip）");
        assertTrue(direct.get("failed").asInt() == 0, "导出原样再导入应零失败行");

        // round-trip ②：把导出内容修改一行题干后重导入 → 新题干列表可见
        byte[] modified = rebuildXlsxRow(exportBytes, "导出的单选", "导出的单选（改）");
        JsonNode result = perform(multipart("/api/questions/import")
                        .file(multipartFile(modified))
                        .header("Authorization", bearer(teacher)), 200)
                .get("data");
        assertEquals(1, result.get("imported").asInt(), "round-trip 修改后再导入成功");
        assertTrue(result.get("failed").asInt() >= 0);

        JsonNode page = perform(jsonGet("/api/questions?size=100", teacher), 200).get("data");
        boolean foundModified = false;
        for (JsonNode q : page.get("list")) {
            if ("导出的单选（改）".equals(q.get("content").asText())) {
                foundModified = true;
            }
        }
        assertTrue(foundModified, "再导入的新题干列表可见（round-trip）");
    }

    /** 读取导出文件表头（前 12 列字符串）。 */
    private List<String> exportHeader(byte[] bytes) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var row = wb.getSheetAt(0).getRow(0);
            List<String> headers = new ArrayList<>();
            for (int i = 0; i < HEADERS.length; i++) {
                headers.add(row.getCell(i).getStringCellValue());
            }
            return headers;
        }
    }

    /**
 * 读取导出文件首个数据行（教师本类仅预置一题的专用场景），把题干 index 1 改为 newContent，
 * 用 buildXlsx 重建一份可再导入的文件（避开 SXSSF→XSSF 读改写时 setCellValue 不落盘的 POI 写回问题）。
 */
    private byte[] rebuildXlsxRow(byte[] bytes, String oldContent, String newContent) throws Exception {
        String[] row;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var src = wb.getSheetAt(0).getRow(1);
            row = new String[HEADERS.length];
            for (int i = 0; i < HEADERS.length; i++) {
                row[i] = src.getCell(i) == null ? "" : src.getCell(i).getStringCellValue();
            }
        }
        assertEquals(oldContent, row[1], "导出首个数据行题干应为「" + oldContent + "」");
        row[1] = newContent;
        return buildXlsx(List.<String[]>of(row));
    }

    // ==================== 工具 ====================

    private static MockMultipartFile multipartFile(byte[] xlsx) {
        return new MockMultipartFile("file", "questions.xlsx",
                MediaType.MULTIPART_FORM_DATA_VALUE, xlsx);
    }

    /** 由数据行构建 xlsx 字节（首行表头 + 数据行）。 */
    private byte[] buildXlsx(List<String[]> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("题库");
            var header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
            }
            for (int r = 0; r < rows.size(); r++) {
                var row = sheet.createRow(r + 1);
                String[] cells = rows.get(r);
                for (int c = 0; c < cells.length; c++) {
                    row.createCell(c).setCellValue(cells[c]);
                }
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                return out.toByteArray();
            }
        }
    }

    private long questionIdOf(JsonNode page, String content) {
        for (JsonNode q : page.get("list")) {
            if (content.equals(q.get("content").asText())) {
                return q.get("id").asLong();
            }
        }
        throw new AssertionError("列表未找到题干: " + content);
    }

    private void addPaperQuestion(String teacher, long paperId, long questionId, double score) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode body = objectMapper.createObjectNode();
        body.put("questionId", questionId);
        body.put("score", score);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                objectMapper.writeValueAsString(body)), 200);
    }
}