package com.exam.deployment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产容器时区词法护栏（fix-prod-container-tz，对应 spec-delta「护栏防回退」Scenario）：
 *
 * <p>为什么需要源码扫描而不是运行期单测：CI 环境不跑 docker，Dockerfile 的变更没有任何
 * 其他机检防线；而运行层若丢失 {@code ENV TZ=Asia/Shanghai}，JVM 回落基础镜像默认 UTC，
 * 考试时间全按上海墙钟 DATETIME 存储比较（22 文件）——prod 定时链路（状态机流转、缺考
 * 锚定、开考提醒、交卷门控、草稿 TTL）整体漂 8 小时，且 dev 宿主机永不复现。本护栏对
 * Dockerfile 做词法级断言：运行层段落必须声明 TZ，删除或改值即确定性失败。
 *
 * <p>断言锚定<b>运行层段落</b>（最后一个 {@code FROM ... AS runtime} 之后），不是全文件：
 * build 层若未来出现同形 TZ 行（如镜像内构建工具需要），不得被误判为运行层合规。
 *
 * <p>局限（如实声明）：词法断言只保证「声明存在且值为 Asia/Shanghai」，不能证明运行期
 * JVM 真以该时区启动，也不能替代 prod 演练环境的 docker exec date 端到端确证——那是
 * evidence/ 的职责；本护栏只保证「最容易复发的删行/改值回退」在提交前就被挡下。
 */
class ProdContainerTzGuardTest {

    /** surefire 的 cwd 是项目根（与 AlertAssetsTest 同一约定） */
    private static final Path DOCKERFILE = Paths.get("Dockerfile");

    /** 运行层阶段头（大小写不敏感；AS 别名可容忍空白差异） */
    private static final Pattern RUNTIME_STAGE_HEADER =
            Pattern.compile("(?im)^FROM\\s+\\S+\\s+AS\\s+runtime\\s*$");

    /** TZ 声明的唯一合规形态：整行 ENV TZ=Asia/Shanghai（注释行已剔除后必须精确命中） */
    private static final Pattern TZ_DECLARATION =
            Pattern.compile("(?im)^ENV\\s+TZ=Asia/Shanghai\\s*$");

    @Test
    @DisplayName("Dockerfile 运行层声明 ENV TZ=Asia/Shanghai（CI 无 docker，部署文件唯一机检防线）")
    void runtimeStageDeclaresShanghaiTz() throws IOException {
        assertTrue(Files.isRegularFile(DOCKERFILE), "缺少 Dockerfile：生产部署栈资产被移动或删除，请人工确认是否有意为之");
        String dockerfile = Files.readString(DOCKERFILE, StandardCharsets.UTF_8);

        String stripped = stripCommentLines(dockerfile);
        Matcher header = RUNTIME_STAGE_HEADER.matcher(stripped);
        int runtimeStart = -1;
        while (header.find()) {
            runtimeStart = header.end();   // 取最后一个运行层头：多阶段下锚定最终运行段
        }
        // 护栏失靶自保护：运行层头消失（改名/删除多阶段结构）本身就必须红，不得静默通过
        assertNotEquals(-1, runtimeStart,
                "Dockerfile 中找不到 FROM ... AS runtime 运行层阶段头：护栏失靶说明多阶段结构被改动，"
                        + "运行层 TZ 锚点不再存在，请人工确认这是不是有意的重构");
        String runtimeStage = stripped.substring(runtimeStart);

        assertTrue(TZ_DECLARATION.matcher(runtimeStage).find(),
                "Dockerfile 运行层缺少 ENV TZ=Asia/Shanghai：JVM 将回落基础镜像默认 UTC，"
                        + "考试定时链路（状态推进/缺考标记/开考提醒/交卷门控/草稿 TTL）整体漂 8 小时"
                        + "（fix-prod-container-tz，spec-delta「护栏防回退」）");
    }

    /** 剔除注释行（整行以 # 开头，含前导空白）：被注释掉的 TZ 声明不得算合规 */
    static String stripCommentLines(String dockerfile) {
        StringBuilder out = new StringBuilder(dockerfile.length());
        for (String line : dockerfile.split("\n", -1)) {
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
