package com.exam.submission.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布确认作用域护栏（fix-broker-confirm-and-dlq-roundtrip，对应 spec-delta「confirm 调用作用域合法」）：
 *
 * <p>为什么需要源码扫描而不是单测：{@code waitForConfirmsOrDie} 只能在
 * {@code RabbitTemplate.invoke()} 作用域内调用，作用域外调用在真 broker 下抛
 * {@code IllegalStateException}——而任何 mock 掉 RabbitTemplate 的测试里它都是无声 no-op，
 * 永远测不出这类违规（遗留 #10 正是这样漏网的）。本护栏对 src/main/java 做词法级断言：
 * 每一处 waitForConfirms* 调用都必须处于某个 invoke(...) 实参区间内，无需真 broker 即可在 CI 检出。
 *
 * <p>局限（如实声明）：词法区间判断不能证明运行期真的处于同一 channel 作用域，也不能替代
 * 真 dev 实例的端到端验证——那是 {@code docs/} 下验证记录的职责；本护栏只保证
 * 「最容易复犯的裸调形态」在提交前就被挡下。
 */
class PublisherConfirmScopeGuardTest {

    /** surefire 的 cwd 是项目根（与 AlertAssetsTest 同一约定） */
    private static final Path MAIN_JAVA = Paths.get("src/main/java");

    /** waitForConfirms / waitForConfirmsOrDie（词边界，避免匹配到 import 或注释里的同名词） */
    private static final Pattern WAIT_FOR_CONFIRMS = Pattern.compile("\\bwaitForConfirms\\w*");

    /** 形如 invoke( 的调用起点（Receiver.invoke(...) 一并覆盖：作用域语义相同） */
    private static final Pattern INVOKE_CALL = Pattern.compile("\\binvoke\\s*\\(");

    record CallSite(String file, int line, int index) {
    }

    record Region(int openParen, int closeParen) {
        boolean contains(int index) {
            return index > openParen && index < closeParen;
        }
    }

    @Test
    @DisplayName("main 源码中所有 waitForConfirms* 调用都处于 invoke(...) 实参区间内")
    void allConfirmWaitsAreInsideInvokeScope() throws IOException {
        List<String> violations = new ArrayList<>();
        int totalCallSites = 0;
        for (SourceFile source : loadMainSources()) {
            for (CallSite site : source.confirmWaitSites()) {
                totalCallSites++;
                boolean inside = source.invokeRegions().stream().anyMatch(r -> r.contains(site.index()));
                if (!inside) {
                    violations.add(source.path() + ":" + site.line()
                            + " waitForConfirms* 调用不在任何 invoke(...) 作用域内（真 broker 下会抛 IllegalStateException）");
                }
            }
        }
        assertTrue(totalCallSites > 0,
                "src/main/java 里一处 waitForConfirms* 都没找到：护栏失靶说明发送器已不再等待 broker confirm，"
                        + "与「发送成功才算补发完成」的可靠性语义冲突，请人工确认这是不是有意的删除");
        assertTrue(violations.isEmpty(),
                "发布确认作用域违规 " + violations.size() + " 处:\n" + String.join("\n", violations));
    }

    @Test
    @DisplayName("ExamSubmitSender 仍在 invoke 作用域内等待 confirm（防静默退化为 fire-and-forget）")
    void submitSenderStillWaitsForBrokerConfirm() throws IOException {
        Path sender = MAIN_JAVA.resolve("com/exam/submission/mq/ExamSubmitSender.java");
        assertTrue(Files.isRegularFile(sender), "缺少 " + sender);
        SourceFile source = new SourceFile(sender.toString(), Files.readString(sender, StandardCharsets.UTF_8));
        assertFalse(source.confirmWaitSites().isEmpty(),
                "ExamSubmitSender 已不含 waitForConfirms* 调用：交卷消息发送退化为 fire-and-forget，"
                        + "「消息到达 broker 才算发送成功」的可靠性约定被破坏（spec「消息可靠落库」）");
        for (CallSite site : source.confirmWaitSites()) {
            assertTrue(source.invokeRegions().stream().anyMatch(r -> r.contains(site.index())),
                    "ExamSubmitSender 第 " + site.line() + " 行的 confirm 等待在 invoke 作用域外");
        }
    }

    // ==================== 词法扫描实现 ====================

    private record SourceFile(String path, String code) {

        /** waitForConfirms* 调用点（注释与字符串已剔除，只可能是真代码） */
        List<CallSite> confirmWaitSites() {
            return sitesOf(WAIT_FOR_CONFIRMS);
        }

        /** 所有 invoke(...) 调用的实参区间 [开括号, 闭括号]（括号配对，嵌套安全） */
        List<Region> invokeRegions() {
            String stripped = stripCommentsAndLiterals(code());
            List<Region> regions = new ArrayList<>();
            Matcher m = INVOKE_CALL.matcher(stripped);
            while (m.find()) {
                int open = m.end() - 1;
                int depth = 0;
                for (int i = open; i < stripped.length(); i++) {
                    char c = stripped.charAt(i);
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                        if (depth == 0) {
                            regions.add(new Region(open, i));
                            break;
                        }
                    }
                }
            }
            return regions;
        }

        private List<CallSite> sitesOf(Pattern pattern) {
            String stripped = stripCommentsAndLiterals(code());
            List<CallSite> sites = new ArrayList<>();
            Matcher m = pattern.matcher(stripped);
            while (m.find()) {
                sites.add(new CallSite(path(), lineAt(stripped, m.start()), m.start()));
            }
            return sites;
        }
    }

    private static List<SourceFile> loadMainSources() throws IOException {
        List<SourceFile> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN_JAVA)) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            for (Path p : files) {
                sources.add(new SourceFile(p.toString(), Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        return sources;
    }

    private static int lineAt(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * 把注释与字符串/字符字面量整体替换为空格（保留换行，保持偏移不变）：
     * 使后续词法匹配只会命中真实代码，注释里提及 API 名不会误报。
     */
    static String stripCommentsAndLiterals(String code) {
        char[] out = code.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i++] = ' ';
                    if (i < n) {
                        out[i++] = ' ';
                    }
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out[i++] = ' ';
                while (i < n && out[i] != quote) {
                    if (out[i] == '\\' && i + 1 < n) {
                        out[i++] = ' ';
                        out[i++] = ' ';
                    } else if (out[i] != '\n') {
                        out[i++] = ' ';
                    } else {
                        i++;    // 字符串跨行（文本块）：保留换行
                    }
                }
                if (i < n) {
                    out[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }
}
