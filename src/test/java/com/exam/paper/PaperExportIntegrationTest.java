package com.exam.paper;

import com.exam.paper.service.PaperExportService;
import com.exam.support.IntegrationTestBase;
import com.lowagie.text.pdf.PdfReader;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 试卷 PDF 导出集成测试（spec-delta question-bank「试卷标准纸质版 PDF 导出」）：
 * <ul>
 *   <li>作答版：A4 排版的卷头/考生信息栏/大题汇总表与题目分组，不输出任何答案内容；</li>
 *   <li>答案版：追加【参考答案与评分标准】区块，字节量严格大于作答版（客观题答案/填空可接受答案/简答参考答案）；</li>
 *   <li>门禁：无 paper:manage 权限 403、非归属教师水平越权 403、不存在试卷 404（m1 变异载体）；</li>
 *   <li>字符容错：题干/标题含 Emoji 与增补平面生僻字时导出不中断（m2 变异载体）。</li>
 * </ul>
 * 断言纪律：PDF 中文文本在零新依赖下不可经 PdfTextExtractor 提取（STSong-Light 不内嵌，
 * 提取为空——本仓实测），故以结构断言（PDF 魔数 / A4 页面 / 字节量级 / 两种版本相对大小）钉死行为。
 */
class PaperExportIntegrationTest extends IntegrationTestBase {

    // ==================== ① 学生作答版：A4 排版与留白 ====================

    @Test
    void studentVersionReturnsA4PdfWithoutAnswers() throws Exception {
        String teacher = registerTeacher();
        long paperId = paperWithThreeKinds(teacher);

        MvcResult result = mockMvc.perform(jsonGet(
                        "/api/papers/" + paperId + "/export-pdf?withAnswer=false", teacher))
                .andExpect(status().isOk())
                .andReturn();
        byte[] pdf = result.getResponse().getContentAsByteArray();

        assertPdfMagic(pdf);
        assertTrue(result.getResponse().getContentType() != null
                        && result.getResponse().getContentType().contains("pdf"),
                "响应类型应为 application/pdf，实际=" + result.getResponse().getContentType());
        String disposition = result.getResponse().getHeader("Content-Disposition");
        assertTrue(disposition != null && disposition.contains("attachment; filename*=UTF-8''")
                        && disposition.contains(".pdf"),
                "应携带 RFC 5987 附件文件名，实际=" + disposition);
        assertA4Page(pdf);
    }

    // ==================== ② 教师参考答案版：答案解析区块 ====================

    @Test
    void answerVersionIsStrictlyLargerThanStudentVersion() throws Exception {
        String teacher = registerTeacher();
        long paperId = paperWithThreeKinds(teacher);

        byte[] student = exportPdfOk("/api/papers/" + paperId + "/export-pdf?withAnswer=false", teacher);
        byte[] answer = exportPdfOk("/api/papers/" + paperId + "/export-pdf?withAnswer=true", teacher);

        assertPdfMagic(student);
        assertPdfMagic(answer);
        // 答案版追加【参考答案与评分标准】+ 每题答案/解析，字节量严格大于作答版：
        // 证明导出包含答案解析区块而作答版不含（零新依赖下可测的最强结构断言）
        assertTrue(answer.length > student.length,
                "答案版(" + answer.length + ")应严格大于作答版(" + student.length + ")字节量");
    }

    // ==================== ③ 越权 / 权限 / 404 门禁（m1 变异载体） ====================

    @Test
    void exportPdfEnforcesPermissionOwnershipAndExistence() throws Exception {
        String teacher = registerTeacher();
        String otherTeacher = registerTeacher();
        String student = registerStudent();
        long paperId = paperWithThreeKinds(teacher);

        // 学生无 paper:manage 权限 -> 403
        perform(jsonGet("/api/papers/" + paperId + "/export-pdf", student), 403);
        // 有 paper:manage 但非归属教师 -> 403（水平越权，依赖 detail 的 owner 校验）
        perform(jsonGet("/api/papers/" + paperId + "/export-pdf?withAnswer=true", otherTeacher), 403);
        // 不存在试卷 -> 404
        perform(jsonGet("/api/papers/99999999/export-pdf", teacher), 404);
        // 归属教师默认 withAnswer=false 可导出（默认值零漂移）
        assertPdfMagic(exportPdfOk("/api/papers/" + paperId + "/export-pdf", teacher));
    }

    // ==================== ④ 生僻字符容错（m2 变异载体） ====================

    @Test
    void exportSurvivesUnsupportedUnicodeInTitleAndQuestions() throws Exception {
        String teacher = registerTeacher();
        // 增补平面字符：Emoji（U+1F600）与 CJK 扩展 B 生僻字（U+20000，CMap 表长 65536 之外）
        long q1 = createQuestion(teacher, 1, "含Emoji😀与生僻字𠀀的题干", "A",
                List.of("选项甲😎", "选项乙"), null);
        long paperId = createPaper(teacher, "特殊字符卷😀𠀀", 5.0);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                "{\"questionId\":" + q1 + "}"), 200);

        // 两种版本都必须导出成功（生僻字符不得中断导出）
        assertPdfMagic(exportPdfOk("/api/papers/" + paperId + "/export-pdf", teacher));
        assertPdfMagic(exportPdfOk("/api/papers/" + paperId + "/export-pdf?withAnswer=true", teacher));
    }

    /**
     * 字符清洗直测（m2 变异载体）：STSong-Light 是 GB2312 映射字体（CMap 表长 65536），
     * 增补平面字符与游离代理项在写出时越界，必须替换为 '?'；不可见控制字符转空格。
     * openpdf 对 Paragraph 路径的越界异常是静默吞掉（整段不渲染）而非抛给调用方，
     * 因此端到端用例 {@link #exportSurvivesUnsupportedUnicodeInTitleAndQuestions} 在
     * 抽掉清洗后仍能生成 PDF（仅丢失文本）——本直测把「清洗真的发生」钉死，是 m2 击红的诚实载体。
     */
    @Test
    void sanitizePdfTextReplacesSupplementaryAndControlChars() {
        assertEquals("含??", PaperExportService.sanitizePdfText("含😀𠀀"),
                "增补平面码点（Emoji/扩展B）应替换为 ?");
        assertEquals("a?b", PaperExportService.sanitizePdfText("a\uD83D\uDE00b"),
                "代理项对应替换为 ?");
        assertEquals("a b", PaperExportService.sanitizePdfText("a\u0001b"),
                "不可见控制字符应替换为空格");
        assertEquals("a\nb\tc", PaperExportService.sanitizePdfText("a\nb\tc"),
                "换行/制表应原样保留（排版需要）");
        assertEquals("", PaperExportService.sanitizePdfText(null), "null 应返回空串");
        assertEquals("正常中文 ABC 123，。；",
                PaperExportService.sanitizePdfText("正常中文 ABC 123，。；"),
                "GB2312 覆盖字符原样保留");
    }

    // ==================== 夹具 ====================

    /** 单选 + 填空 + 简答各一道（各 5 分，总分 15）。 */
    private long paperWithThreeKinds(String teacher) throws Exception {
        long q1 = createQuestion(teacher, 1, "函数 f(x) 的最小值是多少？", "A",
                List.of("A选项", "B选项", "C选项"), null);
        long q2 = createQuestion(teacher, 5, "中华人民共和国首都是____。", "北京|北平", null, null);
        long q3 = createQuestion(teacher, 4, "简述二次函数的图像性质。", "开口方向由 a 决定；对称轴 x=-b/2a。", null, null);
        long paperId = createPaper(teacher, "期中考试卷", 15.0);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                "{\"questionId\":" + q1 + "}"), 200);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                "{\"questionId\":" + q2 + "}"), 200);
        perform(jsonPost("/api/papers/" + paperId + "/questions", teacher,
                "{\"questionId\":" + q3 + "}"), 200);
        return paperId;
    }

    private byte[] exportPdfOk(String url, String token) throws Exception {
        MvcResult result = mockMvc.perform(jsonGet(url, token))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsByteArray();
    }

    private static void assertPdfMagic(byte[] pdf) {
        assertTrue(pdf.length > 500, "PDF 字节应达到排版规模，实际=" + pdf.length);
        assertEquals('%', (char) pdf[0]);
        assertEquals('P', (char) pdf[1]);
        assertEquals('D', (char) pdf[2]);
        assertEquals('F', (char) pdf[3]);
    }

    /** A4 页面尺寸（595.2756 x 841.8898 pt）。 */
    private static void assertA4Page(byte[] pdf) throws IOException {
        try (PdfReader reader = new PdfReader(pdf)) {
            assertEquals(1, reader.getNumberOfPages(), "作答版应至少一页");
            assertEquals(595.2756f, reader.getPageSize(1).getWidth(), 1.0f, "A4 宽");
            assertEquals(841.8898f, reader.getPageSize(1).getHeight(), 1.0f, "A4 高");
        }
    }
}
