package com.exam.taking;

import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「我的考试」列表取数 SQL 捕获（change attribute-my-exams-list-index 阶段 1）：
 * 真实走一遍 GET /api/exam-taking/exams（学生身份），用 MyBatis 拦截器抓窗口内全部
 * BoundSql.getSql() 原文与绑定参数，落盘到测量目录——冻结进 PREREGISTRATION 的是
 * 答卷渠道（T1）与补考渠道（T2）两条目标语句的逐字原文；阶段 3 的 B5④ 以本 IT 复跑
 * 输出与冻结文本逐字比对。
 *
 * <p>本类只捕获、不计时、不裁决；IT 后缀不进 Surefire 全量门禁，仅显式 -Dtest 运行：
 * <pre>
 * mvnw.cmd test -Dtest=MyExamsSqlCaptureIT -DfailIfNoTests=false ^
 *     -Dmeasure.rev=&lt;sha7&gt; -Dmeasure.out=D:\code\examOnline-measure\my-exams-index\raw
 * </pre>
 * 夹具自析构（@AfterEach 清本类固定 ID 段与自建班级），不把发布态夹具留给其他用例。
 */
@DisplayName("我的考试列表 SQL 捕获（仅显式 -Dtest 运行；不进 Surefire 全量门禁）")
class MyExamsSqlCaptureIT extends IntegrationTestBase {

    private static final long ID_LOW = 981_100_000L;
    private static final long ID_HIGH = 981_100_999L;
    private static final long OWNER_ID = 981_100_099L;
    private static final long PAPER_DUMMY = 981_100_090L;

    private static final long EXAM_CLASS_BOUND = 981_100_001L;   // 班级渠道（user_class → class_id）
    private static final long EXAM_ANSWERED = 981_100_002L;      // 答卷渠道（无班级绑定，仅有答卷）
    private static final long EXAM_MAKEUP = 981_100_003L;        // 补考渠道（parent_exam_id 指向主考）

    /** 捕获窗口上下文：MockMvc 同线程串行，静态标志即够（先例 MonitorOverviewProjectionMeasureIT）。 */
    static final class CaptureCtx {
        static volatile boolean capturing;
        static final List<Cap> captured = new ArrayList<>();

        record Cap(String msId, String sql, LinkedHashMap<String, Object> values) {
        }

        static void begin() {
            captured.clear();
            capturing = true;
        }

        static void end() {
            capturing = false;
        }
    }

    @TestConfiguration
    static class MyExamsCaptureConfig {
        @Bean
        Interceptor myExamsSqlCaptureInterceptor() {
            return new MyExamsSqlCaptureInterceptor();
        }
    }

    /** 语句原文捕获（只记录不改变行为）：拦 StatementHandler.prepare，读最终 BoundSql 与绑定参数。 */
    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    static class MyExamsSqlCaptureInterceptor implements Interceptor {

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            if (!CaptureCtx.capturing) {
                return invocation.proceed();
            }
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
            CaptureCtx.captured.add(new CaptureCtx.Cap(ms.getId(), boundSql.getSql(), values));
            return invocation.proceed();
        }
    }

    private final List<Long> createdClasses = new ArrayList<>();

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM exam_candidates WHERE exam_id BETWEEN ? AND ?", ID_LOW, ID_HIGH);
        jdbc.update("DELETE FROM exam_submissions WHERE exam_id BETWEEN ? AND ?", ID_LOW, ID_HIGH);
        jdbc.update("DELETE FROM exams WHERE id BETWEEN ? AND ?", ID_LOW, ID_HIGH);
        for (long classId : createdClasses) {
            jdbc.update("DELETE FROM user_class WHERE class_id = ?", classId);
            jdbc.update("DELETE FROM classes WHERE id = ?", classId);
        }
        createdClasses.clear();
        CaptureCtx.end();
    }

    @Test
    @DisplayName("捕获 myExams 全链路 BoundSql 原文并落盘到测量目录")
    void captureMyExamsSql() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long studentId = studentIdOf(student);
        long classId = createClass(teacher, studentId);

        LocalDateTime now = LocalDateTime.now();
        insertExam(EXAM_CLASS_BOUND, "捕获-班级指派", classId, null,
                Timestamp.valueOf(now.plusHours(3)), Timestamp.valueOf(now.plusHours(5)),
                0 /* 未开始 */);
        insertExam(EXAM_ANSWERED, "捕获-我答过的", null, null,
                Timestamp.valueOf(now.minusDays(40)), Timestamp.valueOf(now.minusDays(40).plusHours(2)),
                2 /* 已结束 */);
        insertExam(EXAM_MAKEUP, "捕获-我的补考", null, EXAM_CLASS_BOUND,
                Timestamp.valueOf(now.minusMinutes(10)), Timestamp.valueOf(now.plusHours(2)),
                1 /* 进行中 */);
        insertSubmission(EXAM_ANSWERED, studentId, 2 /* 已交卷 */);
        insertCandidate(EXAM_MAKEUP, studentId);

        CaptureCtx.begin();
        JsonNode list;
        try {
            list = perform(jsonGet("/api/exam-taking/exams", student), 200).get("data");
        } finally {
            CaptureCtx.end();
        }

        // 三个渠道都要真的发出查询：响应必须同时含三场考试（否则捕获窗口里的 SQL 不全）
        List<Long> ids = new ArrayList<>();
        list.forEach(item -> ids.add(item.get("examId").asLong()));
        assertTrue(ids.contains(EXAM_CLASS_BOUND), "班级渠道考试必须可见；实际=" + ids);
        assertTrue(ids.contains(EXAM_ANSWERED), "答卷渠道考试必须可见；实际=" + ids);
        assertTrue(ids.contains(EXAM_MAKEUP), "补考渠道考试必须可见；实际=" + ids);

        // 结构核对：四张表的查询都必须在窗口内出现（答卷/班级/班级考试/补考/取考试实体 ≥5 条）
        assertTrue(CaptureCtx.captured.stream().anyMatch(c -> c.sql().contains("FROM exam_submissions")),
                "答卷渠道语句未被捕获");
        assertTrue(CaptureCtx.captured.stream().anyMatch(c -> c.sql().contains("FROM user_class")),
                "班级渠道语句未被捕获");
        assertTrue(CaptureCtx.captured.stream().anyMatch(c -> c.sql().contains("FROM exam_candidates")),
                "补考渠道语句未被捕获");
        assertEquals(2, CaptureCtx.captured.stream().filter(c -> c.sql().contains("FROM exams")).count(),
                "exams 语句应恰两条（班级绑定考试 + 取本人考试实体）；实际="
                        + CaptureCtx.captured.stream().filter(c -> c.sql().contains("FROM exams")).count());

        String rev = System.getProperty("measure.rev", "unknown");
        Path out = Paths.get(System.getProperty("measure.out",
                "D:/code/examOnline-measure/my-exams-index/raw"));
        Files.createDirectories(out);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("rev", rev);
        root.put("atIso", Instant.now().toString());
        root.put("studentId", studentId);
        root.put("capturedCount", CaptureCtx.captured.size());
        var arr = root.putArray("captured");
        int seq = 0;
        for (CaptureCtx.Cap c : CaptureCtx.captured) {
            ObjectNode node = arr.addObject();
            node.put("seq", seq++);
            node.put("msId", c.msId());
            node.put("sql", c.sql());
            var vals = node.putObject("values");
            c.values().forEach((k, v) -> vals.put(k, v == null ? null : String.valueOf(v)));
        }
        Path target = out.resolve("my-exams-boundsql.json");
        Files.writeString(target, objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
        System.out.println("[MyExamsSqlCaptureIT] BoundSql captured -> " + target.toAbsolutePath());
    }

    // ==================== 工具（与 MyExamsListScopeIntegrationTest 同口径） ====================

    /** 当前登录用户 ID（/api/auth/me）。 */
    private long studentIdOf(String token) throws Exception {
        return perform(jsonGet("/api/auth/me", token), 200).get("data").get("id").asLong();
    }

    /** 建班并把给定学生入班（真实指派渠道：user_class），返回班级 ID。 */
    private long createClass(String teacher, long... studentIds) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", unique("班"));
        long newClassId = perform(jsonPost("/api/classes", teacher,
                objectMapper.writeValueAsString(body)), 200).get("data").get("id").asLong();
        createdClasses.add(newClassId);
        for (long sid : studentIds) {
            ObjectNode join = objectMapper.createObjectNode();
            join.put("userId", sid);
            perform(jsonPost("/api/classes/" + newClassId + "/students", teacher,
                    objectMapper.writeValueAsString(join)), 200);
        }
        return newClassId;
    }

    private void insertExam(long id, String title, Long classId, Long parentExamId,
                            Timestamp start, Timestamp end, int status) {
        jdbc.update("INSERT INTO exams (id, title, paper_id, class_id, parent_exam_id, start_time,"
                        + " end_time, duration_minutes, allow_late_minutes, status, published, force_end,"
                        + " version, created_by, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,?,60,0,?,1,0,0,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, title, PAPER_DUMMY, classId, parentExamId, start, end, status, OWNER_ID);
    }

    private void insertSubmission(long examId, long studentId, int status) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                        + " status, version, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                examId, studentId, now, now, status);
    }

    private void insertCandidate(long examId, long studentId) {
        jdbc.update("INSERT INTO exam_candidates (exam_id, student_id, created_time)"
                + " VALUES (?,?,CURRENT_TIMESTAMP)", examId, studentId);
    }
}
