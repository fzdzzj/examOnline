package com.exam.score.measure;

import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.score.dto.MyScoreResponse;
import com.exam.score.service.ScoreQueryService;
import com.exam.submission.entity.ExamSubmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * myScore 名次聚合计数的 MyBatis 侧语句捕获（attribute-my-score-count-index 归因证据工具）。
 *
 * <p><b>为什么单独一个类名以 IT 结尾</b>：Surefire 默认只收 {@code *Test} 等四类命名，本类不进
 * {@code mvnw clean test} 全量门禁，只有显式 {@code -Dtest=MyScoreCountCaptureMeasureIT} 才运行。
 * 它只捕获语句、不构成任何业务断言基线（语义自检仅用于证明捕获走的是生产链路）。
 *
 * <p><b>一次运行量三件事</b>：
 * <ol>
 *   <li>按既有 {@code RankAttributionMeasureIT} 同口径造数（每 exam_id 50/200/1000/3000 行，
 *       status=3、total_score=objective_score=score(i)、subjective_score=0、大量并列且全非空）；</li>
 *   <li>直调生产 {@code ScoreService.myScore}（SecurityUtil ThreadLocal 注入合成学生身份），
 *       由测试侧 StatementHandler 级拦截器捕获该调用链发出的<b>每一条</b>语句原文与绑定参数——
 *       不手写近似 SQL，捕获的是 MyBatis-Plus 实际发给 JDBC 的 BoundSql；</li>
 *   <li>逐形状做语义自检（rank == 独立推算的 count(total_score &gt; 本人分)+1）并落盘机器可读 JSON，
 *       其中 COUNT 语句附「? → 字面量」机械替换版，供 MySQL 容器侧逐字执行。</li>
 * </ol>
 *
 * <p><b>不触碰生产代码</b>：不改 {@code src/main}、SQL、schema、Mapper、JVM/线程池配置；拦截器只在
 * 本测试的 {@code @TestConfiguration} 内注册，仅本进程生效。<b>H2 在这里只是承载捕获的传输层</b>：
 * 回表类归因证据一律来自后续临时 MySQL 容器上的 EXPLAIN/EXPLAIN ANALYZE，本工具的 H2 计时不是证据。
 *
 * <p><b>运行</b>（仓库根）：
 * <pre>
 * mvnw.cmd -q test -Dtest=MyScoreCountCaptureMeasureIT -DfailIfNoTests=false ^
 *     -Dmeasure.rev=&lt;sha7&gt; -Dmeasure.label=cap1 -Dmeasure.out=D:\code\examOnline-measure\myscore-count-attr
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("myScore 名次 COUNT 语句捕获（仅显式 -Dtest 运行；不进 Surefire 全量门禁）")
class MyScoreCountCaptureMeasureIT {

    /** 班规模梯度，沿用 RankAttributionMeasureIT 口径。 */
    private static final int[] SIZES = {50, 200, 1000, 3000};
    /** 考试 ID 段位：930000000 + n。 */
    private static final long EXAM_ID_BASE = 930_000_000L;
    /** 合成学生 ID 段位：本工具不登录，全部为合成 id（无 users 行，myScore 不读 users）。 */
    private static final long STUDENT_ID_BASE = 930_000_000L;
    /** 被捕获的目标语句：myScore 名次聚合计数。 */
    private static final String COUNT_ID = "com.exam.grading.mapper.GradingSubmissionMapper.selectCount";

    private static final String DATA_RULE =
            "不登录（无 users 行；myScore 不读 users）；exams 行直插 id=930000000+n、status=4/published=1、"
                    + "paper_id=930000001(占位无外键)；exam_submissions 逐份直插 exam_id=930000000+n、status=3(已批改)、"
                    + "grading_status=1、total_score=objective_score=score(i)=BigDecimal.valueOf(400+(i*7919)%300, 1)"
                    + "（值域 40.0–69.9、300 个取值，大量并列且全非 null）、subjective_score=0.0；"
                    + "student_id=930000000+n*100000+i（全部合成；本人取下标 0，对应本人分=40.0=全场最低分）。"
                    + "paper_json/answers 保持 NULL（同既有 IT 口径；生产已批改行这两列非空、行更宽，"
                    + "回表代价只高不低，故本测量对候选收益的估计偏保守）。";

    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ScoreQueryService scoreQueryService;

    /** 测试侧捕获拦截器：StatementHandler.prepare 时读取最终 BoundSql 与绑定参数，仅本上下文生效。 */
    @TestConfiguration
    static class CaptureConfig {
        @Bean
        Interceptor sqlCaptureInterceptor() {
            return new SqlCaptureInterceptor();
        }
    }

    /**
     * 语句原文捕获（测试侧，只记录不改变任何行为）：拦 {@code StatementHandler.prepare}——
     * 此时 handler 构造期已算好本次执行最终使用的 BoundSql（含 MyBatis-Plus wrapper 的
     * MPGENVAL 参数键），避免在 Executor 层二次 {@code getBoundSql} 造成 wrapper 参数键漂移。
     */
    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    static class SqlCaptureInterceptor implements Interceptor {

        static final List<Captured> CAPTURED = Collections.synchronizedList(new ArrayList<>());

        static void reset() {
            CAPTURED.clear();
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            // 解开可能的 Plugin 代理链（生产 SlowSqlInterceptor 也在 StatementHandler 级，
            // 本上下文里 target 可能是被包了一层或多层的 JDK 代理）
            Object t = invocation.getTarget();
            for (int i = 0; i < 5 && Proxy.isProxyClass(t.getClass()); i++) {
                InvocationHandler h = Proxy.getInvocationHandler(t);
                if (!(h instanceof Plugin)) {
                    break;
                }
                t = SystemMetaObject.forObject(h).getValue("target");
            }
            MetaObject mo = SystemMetaObject.forObject(t);
            if (mo.hasGetter("delegate")) {
                mo = SystemMetaObject.forObject(mo.getValue("delegate"));
            }
            MappedStatement ms = (MappedStatement) mo.getValue("mappedStatement");
            BoundSql boundSql = (BoundSql) mo.getValue("boundSql");
            ParameterHandler parameterHandler = (ParameterHandler) mo.getValue("parameterHandler");
            Object parameterObject = parameterHandler.getParameterObject();

            // 逐位绑定值解析：与 DefaultParameterHandler.setParameters 同一判定顺序
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            TypeHandlerRegistry registry = ms.getConfiguration().getTypeHandlerRegistry();
            for (ParameterMapping pm : boundSql.getParameterMappings()) {
                String prop = pm.getProperty();
                Object value;
                if (boundSql.hasAdditionalParameter(prop)) {
                    value = boundSql.getAdditionalParameter(prop);
                } else if (parameterObject == null) {
                    value = null;
                } else if (registry.hasTypeHandler(parameterObject.getClass())) {
                    value = parameterObject;
                } else {
                    value = ms.getConfiguration().newMetaObject(parameterObject).getValue(prop);
                }
                values.put(prop, value);
            }
            CAPTURED.add(new Captured(ms.getId(), boundSql.getSql(), values));
            return invocation.proceed();
        }

        record Captured(String msId, String sql, LinkedHashMap<String, Object> values) {
        }
    }

    @Test
    @DisplayName("逐形状捕获 myScore 全部语句原文并落盘机器可读 JSON")
    void capture() throws Exception {
        String rev = System.getProperty("measure.rev", "unknown");
        String label = System.getProperty("measure.label", "rev" + rev);
        Path outDir = Paths.get(System.getProperty("measure.out", "target/measure"));
        Files.createDirectories(outDir);

        prepareData();

        ObjectNode root = om.createObjectNode();
        root.put("rev", rev);
        root.put("label", label);
        root.put("generatedAtIso", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        root.put("engineNote", "capture-only: statements are captured on the H2 test context "
                + "(transport only); back-to-table attribution evidence comes from the temporary "
                + "MySQL container run, not from H2 timing or H2 EXPLAIN.");
        root.put("dataRule", DATA_RULE);
        root.put("captureMechanism", "StatementHandler.prepare interceptor reading the final "
                + "BoundSql + DefaultParameterHandler-equivalent value resolution; no hand-written SQL.");
        ArrayNode shapes = root.putArray("shapes");

        for (int n : SIZES) {
            long examId = EXAM_ID_BASE + n;
            long ownStudentId = STUDENT_ID_BASE + (long) n * 100000L + 0;
            BigDecimal ownScore = score(0);

            SqlCaptureInterceptor.reset();
            SecurityUtil.set(loginAs(ownStudentId));
            MyScoreResponse response;
            try {
                response = scoreQueryService.myScore(examId);
            } finally {
                SecurityUtil.clear();
            }

            // 语义自检：rank == 独立推算的 count(total_score > 本人分) + 1
            int expectedRank = strictlyHigherCount(n, ownScore) + 1;
            assertEquals(expectedRank, response.getRank(), "rank 不符 n=" + n);
            assertFalse(Boolean.TRUE.equals(response.getReviewing()), "不应处于复核中 n=" + n);
            assertTrue(ownScore.compareTo(response.getTotalScore()) == 0,
                    "本人总分与捕获预期不符 n=" + n);

            List<SqlCaptureInterceptor.Captured> captured =
                    new ArrayList<>(SqlCaptureInterceptor.CAPTURED);
            List<SqlCaptureInterceptor.Captured> counts = captured.stream()
                    .filter(c -> COUNT_ID.equals(c.msId()))
                    .toList();
            assertEquals(1, counts.size(), "selectCount 语句应恰好出现一次 n=" + n);
            SqlCaptureInterceptor.Captured countStmt = counts.get(0);

            String literalSql = toLiteralSql(countStmt);
            System.out.println("CAPTURE n=" + n + " statements=" + captured.size()
                    + " rank=" + response.getRank());
            System.out.println("CAPTURE n=" + n + " countSql=" + countStmt.sql());
            System.out.println("CAPTURE n=" + n + " countValues=" + countStmt.values());
            System.out.println("CAPTURE n=" + n + " countLiteralSql=" + literalSql);

            ObjectNode shapeNode = shapes.addObject();
            shapeNode.put("n", n);
            shapeNode.put("examId", examId);
            shapeNode.put("ownStudentId", ownStudentId);
            shapeNode.put("ownTotalScore", ownScore.toPlainString());
            shapeNode.put("expectedRank", expectedRank);
            shapeNode.put("responseRank", response.getRank());
            shapeNode.put("reviewing", Boolean.TRUE.equals(response.getReviewing()));
            ArrayNode stmts = shapeNode.putArray("statements");
            for (SqlCaptureInterceptor.Captured c : captured) {
                ObjectNode sn = stmts.addObject();
                sn.put("msId", c.msId());
                sn.put("sql", c.sql());
                ObjectNode vn = sn.putObject("values");
                for (Map.Entry<String, Object> e : c.values().entrySet()) {
                    vn.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
                }
            }
            ObjectNode cn = shapeNode.putObject("countStatement");
            cn.put("msId", countStmt.msId());
            cn.put("sql", countStmt.sql());
            ObjectNode cvn = cn.putObject("values");
            for (Map.Entry<String, Object> e : countStmt.values().entrySet()) {
                cvn.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
            cn.put("literalSql", literalSql);
        }

        Path out = outDir.resolve("myscore-count-capture-" + label + ".json");
        Files.writeString(out, om.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
        System.out.println("CAPTURE written " + out.toAbsolutePath());
    }

    // ==================== 造数与参照（与 RankAttributionMeasureIT 同口径） ====================

    /** 确定性分数列表：score(i)=BigDecimal.valueOf(400+(i*7919)%300, 1)（值域 40.0–69.9）。 */
    private static List<BigDecimal> scores(int n) {
        List<BigDecimal> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(score(i));
        }
        return list;
    }

    private static BigDecimal score(int i) {
        return BigDecimal.valueOf(400 + (i * 7919L) % 300, 1);
    }

    /** 独立参照：本人分严格更高的行数（竞赛排名口径的 count 部分）。 */
    private static int strictlyHigherCount(int n, BigDecimal ownScore) {
        int count = 0;
        for (BigDecimal s : scores(n)) {
            if (s.compareTo(ownScore) > 0) {
                count++;
            }
        }
        return count;
    }

    private static final String INSERT_SUBMISSION =
            "INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time, status,"
                    + " objective_score, subjective_score, total_score, grading_status, partial_graded,"
                    + " version, created_time, updated_time)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";

    /** 每个规模一场考试 + n 份已批改答卷（本人=下标 0 的合成学生）。 */
    private void prepareData() {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (int n : SIZES) {
            long examId = EXAM_ID_BASE + n;
            jdbc.update("DELETE FROM exam_submissions WHERE exam_id = ?", examId);
            jdbc.update("DELETE FROM exams WHERE id = ?", examId);
            jdbc.update("INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes,"
                            + " status, published, created_by, version, created_time, updated_time)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    examId, "myscore-count-capture-n" + n, 930000001L, now, now, 60, 4, 1, 930000999L, 0);

            List<Object[]> batch = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                BigDecimal score = score(i);
                long studentId = STUDENT_ID_BASE + (long) n * 100000L + i;
                batch.add(new Object[]{examId, studentId, now, now,
                        ExamSubmission.STATUS_GRADED, score, BigDecimal.ZERO.setScale(1), score, 1, 0});
            }
            jdbc.batchUpdate(INSERT_SUBMISSION, batch);
        }
    }

    /** ThreadLocal 登录身份（合成学生；myScore 只用 id 定位答卷行）。 */
    private static LoginUser loginAs(long studentId) {
        LoginUser user = new LoginUser();
        user.setId(studentId);
        user.setRoleLevel(RoleHierarchy.levelOf(RoleHierarchy.STUDENT));
        return user;
    }

    /**
     * 「? → 字面量」机械替换（按 parameterMapping 顺序逐位替换，替换次数必须与参数数一致）。
     * 只产生容器侧执行的等价文本，SQL 形状零改动。
     */
    private static String toLiteralSql(SqlCaptureInterceptor.Captured c) {
        String sql = c.sql();
        int q = countOccurrences(sql, '?');
        assertEquals(c.values().size(), q,
                "参数位数与 ? 数不一致：" + c.values().size() + " vs " + q);
        StringBuilder sb = new StringBuilder(sql.length() + 64);
        int idx = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '?') {
                sb.append(formatLiteral((Object) c.values().values().toArray()[idx++]));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static int countOccurrences(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    private static String formatLiteral(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        if (v instanceof Timestamp ts) {
            return "'" + ts.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "'";
        }
        String s = v.toString().replace("'", "''");
        return "'" + s + "'";
    }
}
