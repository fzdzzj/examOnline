package com.exam.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SMTP 三向超时词法护栏（fix-mail-smtp-timeout-and-catch，先例 ProdContainerTzGuardTest）：
 *
 * <p>为什么需要源码扫描而不是只测绑定：测试上下文只装载 base {@code application.yml} + test 覆盖层，
 * {@code application-dev.yml} / {@code application-prod.yml} 的绑定不在任何测试上下文里——删行/改键名
 * 这类回退在生产/dev 档没有任何机检防线。本护栏对三处 profile 配置做词法级断言：
 * {@code mail.smtp.connectiontimeout} / {@code mail.smtp.timeout} / {@code mail.smtp.writetimeout}
 * 三键必须全部声明且值为正整数，缺失或清零即确定性失败。
 *
 * <p>局限（如实声明）：词法断言只保证「声明存在且值为正整数」，不能证明运行期 JavaMail 真以该超时
 * 运行，也不能替 dev/prod 档做绑定级验证——base yml 的生效层由 {@link MailSmtpTimeoutConfigTest}
 * 的 {@code MailProperties} 装配断言补足；本护栏只保证「最容易复发的删行/改键」在提交前就被挡下。
 */
class MailSmtpTimeoutGuardTest {

    /** surefire 的 cwd 是项目根（与 ProdContainerTzGuardTest 同一约定） */
    private static final Path REPO_ROOT = Paths.get("");

    private static final String[] PROFILE_FILES = {
            "src/main/resources/application.yml",
            "src/main/resources/application-dev.yml",
            "src/main/resources/application-prod.yml",
    };

    /** 键 → 该键在剔除注释后的行里必须匹配的形态（冒号后正整数值，不含默认空值形态） */
    private static final String[] TIMEOUT_KEYS = {
            "mail.smtp.connectiontimeout",
            "mail.smtp.timeout",
            "mail.smtp.writetimeout",
    };

    /** 单键行形态：缩进 + 键 + 冒号 + 至少两位正整数值（10000 缺省） */
    private static Pattern keyLine(String key) {
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + ":\\s*[0-9]{2,}\\s*$");
    }

    @Test
    @DisplayName("三处 profile 的 spring.mail.properties 均声明 SMTP 三向超时（CI 唯一机检防线）")
    void allThreeProfilesDeclareSmtpTimeouts() throws IOException {
        for (String file : PROFILE_FILES) {
            Path path = REPO_ROOT.resolve(file);
            assertTrue(Files.isRegularFile(path),
                    "缺少 " + file + "：profile 配置资产被移动或删除，请人工确认是否有意为之");
            String stripped = stripCommentLines(Files.readString(path, StandardCharsets.UTF_8));
            for (String key : TIMEOUT_KEYS) {
                Matcher m = keyLine(key).matcher(stripped);
                assertTrue(m.find(),
                        file + " 缺少 " + key + " 的正整数值声明：JavaMail 该方向将回落无限等待，"
                                + "SMTP 故障时找回密码请求线程挂起累积（fix-mail-smtp-timeout-and-catch）");
            }
        }
    }

    /** 剔除注释行（整行行首有效字符为 #）：被注释掉的声明不得算合规 */
    static String stripCommentLines(String content) {
        StringBuilder out = new StringBuilder(content.length());
        for (String line : content.split("\n", -1)) {
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
