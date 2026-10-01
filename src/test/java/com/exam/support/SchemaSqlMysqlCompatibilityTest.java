package com.exam.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * schema.sql 的 MySQL 8 方言护栏（fix-schema-mysql-pk, W16）。
 *
 * <p>为什么要有这个测试：MySQL 8 要求 AUTO_INCREMENT 列必须是某个 key 的一部分（错误 1075），
 * 而 H2(MODE=MySQL) 容忍「自增列无主键」的写法。此前 25 张表全部漏写 PRIMARY KEY，
 * 集成测试照样全绿，空 MySQL 库却一条 CREATE TABLE 都执行不了——典型的「测试绿、新库起不来」。
 *
 * <p>本测试只读 schema.sql 文本、按 CREATE TABLE 块切分做静态断言，不连数据库：
 * 任何含 AUTO_INCREMENT 的建表块必须有 {@code PRIMARY KEY (id)}，缺一块就红并打出表名。
 */
class SchemaSqlMysqlCompatibilityTest {

    private static final Pattern TABLE_NAME =
            Pattern.compile("^CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+([A-Za-z_][A-Za-z0-9_]*)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern PRIMARY_KEY_ON_ID =
            Pattern.compile("PRIMARY\\s+KEY\\s*\\(\\s*id\\s*\\)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("schema.sql 中每个含 AUTO_INCREMENT 的建表块都必须声明 PRIMARY KEY (id)")
    void everyAutoIncrementTableDeclaresPrimaryKey() throws IOException {
        String schema = readSchemaSql();
        List<String> autoIncrementTables = new ArrayList<>();
        List<String> missingPrimaryKey = new ArrayList<>();

        String table = null;
        StringBuilder block = new StringBuilder();
        for (String rawLine : schema.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("--")) {
                continue;   // 注释不参与块切分（注释里也会提到 CREATE TABLE）
            }
            if (table == null) {
                Matcher matcher = TABLE_NAME.matcher(line);
                if (matcher.find()) {
                    table = matcher.group(1);
                    block.setLength(0);
                }
                continue;
            }
            if (line.startsWith(");")) {
                String body = block.toString();
                if (body.toUpperCase(Locale.ROOT).contains("AUTO_INCREMENT")) {
                    autoIncrementTables.add(table);
                    if (!PRIMARY_KEY_ON_ID.matcher(body).find()) {
                        missingPrimaryKey.add(table);
                    }
                }
                table = null;
                continue;
            }
            block.append(line).append('\n');
        }

        assertTrue(autoIncrementTables.size() >= 25,
                "schema.sql 解析出的含 AUTO_INCREMENT 建表块只有 " + autoIncrementTables.size()
                        + " 个（期望至少 25），可能是切块逻辑或脚本本身出问题：" + autoIncrementTables);
        assertTrue(missingPrimaryKey.isEmpty(),
                "以下表的 AUTO_INCREMENT 列没有 PRIMARY KEY (id)，MySQL 8 空库执行 schema.sql 会报 1075，"
                        + "缺主键的表：" + missingPrimaryKey);
    }

    private String readSchemaSql() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            assertNotNull(in, "classpath 下找不到 schema.sql（测试建表的唯一来源）");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}