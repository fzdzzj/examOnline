package com.exam.score.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ScoreExportService#sanitizePdfText} 单元护栏
 * （harden-review-flow-and-resource-limits L-02）：
 *
 * <p>STSong-Light + UniGB-UCS2-H（CMap 表长 65536）遇增补平面字符必崩，清洗必须：
 * 增补平面码点/游离代理项 → '?'；不可见 ISO 控制字符 → 空格；换行/制表/回车与
 * 常规中文、ASCII、GB2312 覆盖的 BMP 特殊符号原样保留（既不误伤可渲染内容，
 * 也不放过任何会让 openpdf 越界的字符）。
 */
class ScoreExportServiceSanitizePdfTextUnitTest {

    @Test
    @DisplayName("增补平面 Emoji 与游离代理项替换为 ?，不可见控制字符替换为空格")
    void sanitizePdfText_replacesUnsupportedAndControlChars() {
        // Emoji（U+1F600 笑脸）→ '?'；游离高位代理 → '?'
        assertEquals("正常?题目", ScoreExportService.sanitizePdfText("正常\uD83D\uDE00题目"));
        assertEquals("a?b", ScoreExportService.sanitizePdfText("a\uD83D\uDE03b"));
        assertEquals("x?y", ScoreExportService.sanitizePdfText("x\uD800y"));
        // 不可见控制字符（\u0001 \u001F）→ 空格；换行/制表/回车保留
        assertEquals("a b", ScoreExportService.sanitizePdfText("a\u0001b"));
        assertEquals("a\nb\tc", ScoreExportService.sanitizePdfText("a\nb\tc"));
    }

    @Test
    @DisplayName("常规中文、ASCII 与 GB2312 覆盖的 BMP 特殊符号原样保留")
    void sanitizePdfText_keepsSupportedChars() {
        String supported = "期中考试A1，∑∅∈∞±√≤≥①(½)～×—…";
        assertEquals(supported, ScoreExportService.sanitizePdfText(supported));
        assertEquals("", ScoreExportService.sanitizePdfText(null));
        assertEquals("", ScoreExportService.sanitizePdfText(""));
    }
}
