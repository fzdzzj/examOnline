package com.exam.anticheat.service;

import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.anticheat.dto.BehaviorLogItem;
import com.exam.anticheat.dto.BehaviorTimelineResponse;
import com.exam.exam.entity.Exam;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行为时间线明细上限与统计口径护栏（change harden-behavior-report-and-monitor，
 * spec-delta「时间线明细行数上限与统计口径保全」）：
 *
 * <p>{@link BehaviorLogQueryService#timeline} 此前一条无上限 {@code selectList} 同时喂 total/严重度分布/items
 * 且逐行载 {@code event_data} TEXT——与上报刷写叠加后单人时间线查询膨胀（同文件 page 已有 size≤200 先例）。
 * 本测试用 Executor 层取数护栏把改造后的形状钉死：
 * <ol>
 *   <li><b>统计窄投影</b>：恰一次统计查询，全量行（无 LIMIT，否则计数失真）、只取 severity 列
 *       （不载 event_data TEXT）；total/low/medium/highCount 由它得出；</li>
 *   <li><b>明细上限</b>：恰一次明细查询带 LIMIT 1000，event_time/id 升序，items 如实反映实际条数；</li>
 *   <li><b>红线</b>：统计口径不因明细截断而失真（1005 行时 total=1005、items=1000）；
 *       存量少行数据下不多不少全返回。</li>
 * </ol>
 * 行数以 H2 真实表为准（seed 前置断言库内行数），不依赖 mock——落库语义（未注册类型照常落库）
 * 由 {@code BehaviorEventCollectServiceTest} 与既有集成用例保证。
 */
@SpringBootTest
@ActiveProfiles("test")
// 调度隔离：考试状态机/兜底扫描在观测窗口内不得有定时流量
@TestPropertySource(properties = {
        "exam.schedule.initial-delay-ms=3600000",
        "exam.schedule.fixed-delay-ms=3600000"
})
@DisplayName("行为时间线：统计窄投影全量 + 明细 LIMIT 1000 升序，统计不因截断失真")
class BehaviorLogQueryServiceTest {

    private static final long OWNER_ID = 980_400_000L;
    private static final long EXAM_ID = 980_400_001L;
    private static final long STUDENT_ID = 980_400_101L;
    /** 明细上限（与实现常量 TIMELINE_ITEM_LIMIT 对齐）。 */
    private static final int ITEM_LIMIT = 1000;
    /** 超过上限的样本量：证明"截断"确实发生（否则 total=items，两个口径无法区分）。 */
    private static final int SEED_ROWS = 1005;
    /** event_type 列 VARCHAR(32)：未注册类型也必须能照常落库（兜底策略），长度控制在 20 内。 */
    private static final String UNKNOWN_TYPE_PREFIX = "FUTURE_EVENT_";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BehaviorLogQueryService queryService;

    @TestConfiguration
    static class GuardConfig {
        @Bean
        Interceptor behaviorLogReadGuardInterceptor() {
            return new BehaviorLogReadGuardInterceptor();
        }
    }

    @BeforeEach
    void resetGuard() {
        BehaviorLogReadGuardInterceptor.reset();
    }

    @Test
    @DisplayName("1005 行：统计查询全量窄投影，明细查询 LIMIT 1000 升序，total 与分布不失真")
    void timelineCapsItemsButKeepsStatisticsFaithful() {
        seedExam();
        // 前 1000 行（event_time 较早）各占 low/medium/high；后 5 行 event_time 最晚且全为 high——
        // 若明细上限失效则 items 会带上这 5 行，若升序失效则 items 头几条就不是最早的 low 行。
        // 固定基准时间（不用 now 相对值）：截断后的首尾条目时间可精确断言
        LocalDateTime base = LocalDateTime.of(2026, 5, 1, 10, 0);
        int inserted = seedLogs(base, SEED_ROWS);
        assertEquals(SEED_ROWS, count("SELECT COUNT(*) FROM exam_behavior_logs WHERE exam_id = ?"
                + " AND student_id = ?", EXAM_ID, STUDENT_ID), "seed 前置：库内行数");
        assertTrue(inserted == SEED_ROWS);

        BehaviorTimelineResponse response = timelineAsTeacher();

        // ---- 取数护栏：两次查询、各自形状 ----
        assertEquals(1, BehaviorLogReadGuardInterceptor.PROJECTION_EXECUTIONS.get(),
                "统计查询必须恰一次（拆两查而非逐行统计）");
        assertNotNull(BehaviorLogReadGuardInterceptor.projectionSql, "canary：须捕获统计查询 SQL");
        String projectionSql = BehaviorLogReadGuardInterceptor.projectionSql.toLowerCase(Locale.ROOT);
        assertEquals(SEED_ROWS, BehaviorLogReadGuardInterceptor.PROJECTION_ROWS.get(),
                "统计查询必须全量（无 LIMIT 截断），否则 total/严重度分布必然失真");
        assertFalse(projectionSql.contains("limit"),
                "统计查询不得带行数上限：" + BehaviorLogReadGuardInterceptor.projectionSql);
        assertTrue(projectionSql.matches("(?s).*select\\s+severity\\s+from\\s+exam_behavior_logs.*"),
                "统计查询必须是仅取 severity 列的窄投影（不载 event_data TEXT）："
                        + BehaviorLogReadGuardInterceptor.projectionSql);
        assertFalse(projectionSql.contains("event_data"),
                "统计查询不得载入 event_data：" + BehaviorLogReadGuardInterceptor.projectionSql);

        assertEquals(1, BehaviorLogReadGuardInterceptor.DETAIL_EXECUTIONS.get(),
                "明细查询必须恰一次");
        assertNotNull(BehaviorLogReadGuardInterceptor.detailSql, "canary：须捕获明细查询 SQL");
        String detailSql = BehaviorLogReadGuardInterceptor.detailSql.toLowerCase(Locale.ROOT);
        assertTrue(detailSql.contains("limit"),
                "明细查询必须带行数上限：" + BehaviorLogReadGuardInterceptor.detailSql);
        assertTrue(detailSql.contains("order by event_time") && detailSql.contains("id asc"),
                "明细查询必须 event_time/id 升序（截断后仍是最早的 1000 条）："
                        + BehaviorLogReadGuardInterceptor.detailSql);

        // ---- 响应口径 ----
        long distribution = response.getLowCount() + response.getMediumCount() + response.getHighCount();
        assertEquals(SEED_ROWS, response.getTotal(), "total 取自全量统计，不得等于 items 条数");
        assertEquals(ITEM_LIMIT, response.getItems().size(),
                "items 必须被截断到上限（" + SEED_ROWS + " 行 → " + ITEM_LIMIT + " 条）");
        assertEquals(SEED_ROWS, distribution, "严重度分布来自全量统计，截断不得改口径");
        // 分布锚定「全量统计」口径：明细只截最早的 1000 行，统计若被截断污染则三项之和掉到 1000。
        int lowRows = count("SELECT COUNT(*) FROM exam_behavior_logs WHERE exam_id = ? AND student_id = ?"
                + " AND severity = 1", EXAM_ID, STUDENT_ID);
        int mediumRows = count("SELECT COUNT(*) FROM exam_behavior_logs WHERE exam_id = ? AND student_id = ?"
                + " AND severity = 2", EXAM_ID, STUDENT_ID);
        int highRows = count("SELECT COUNT(*) FROM exam_behavior_logs WHERE exam_id = ? AND student_id = ?"
                + " AND severity = 3", EXAM_ID, STUDENT_ID);
        assertEquals(SEED_ROWS, lowRows + mediumRows + highRows, "seed 前置：三档严重度覆盖全部行");
        assertEquals(lowRows, response.getLowCount(), "low 取自全量统计（与库内 COUNT 一致，不受明细截断影响）");
        assertEquals(mediumRows, response.getMediumCount(), "medium 取自全量统计");
        assertEquals(highRows, response.getHighCount(), "high 取自全量统计");
        assertEquals(SEED_ROWS, response.getLowCount() + response.getMediumCount() + response.getHighCount(),
                "三档之和必须等于全量 " + SEED_ROWS + "（明细只有 1000 条 ⇒ 统计未被明细截断污染）");
        List<BehaviorLogItem> items = response.getItems();
        assertEquals(LocalDateTime.of(2026, 5, 1, 10, 0), items.get(0).getEventTime(),
                "首条必须是最早事件（升序 + 只截尾部）");
        // 第 1000 行 = base + 999 分钟 = 2026-05-01 10:00 + 16h39m；后 5 行（更晚）被上限截掉
        assertEquals(LocalDateTime.of(2026, 5, 2, 2, 39), items.get(ITEM_LIMIT - 1).getEventTime(),
                "末条必须是第 1000 行（更晚的 5 行被上限截掉）");
        LocalDateTime latestInDb = jdbc.queryForObject(
                "SELECT MAX(event_time) FROM exam_behavior_logs WHERE exam_id = ? AND student_id = ?",
                LocalDateTime.class, EXAM_ID, STUDENT_ID);
        assertNotNull(latestInDb, "canary：库内须有更晚的行可被截掉");
        assertTrue(items.get(items.size() - 1).getEventTime().isBefore(latestInDb),
                "截断方向必须是保留最早的 1000 条：末条 " + items.get(items.size() - 1).getEventTime()
                        + " 应早于库内最晚行 " + latestInDb);
        assertTrue(items.stream().allMatch(i -> i.getEventData() != null
                        && i.getEventData().toString().contains("payload")),
                "明细查询仍须载入 event_data（本次只加行数上限，不缩减对外字段）");
        assertTrue(items.stream().anyMatch(i -> i.getEventType().startsWith(UNKNOWN_TYPE_PREFIX)),
                "未注册事件类型照常落库并出现在时间线（兜底策略红线）");
    }

    @Test
    @DisplayName("存量少行：不触上限时 items 全量返回且与统计一致（上限不得误杀正常场景）")
    void timelineBelowLimitReturnsEverything() {
        seedExam();
        int inserted = seedLogs(LocalDateTime.of(2026, 5, 1, 10, 0), 7);
        assertEquals(7, inserted);

        BehaviorTimelineResponse response = timelineAsTeacher();

        assertEquals(1, BehaviorLogReadGuardInterceptor.PROJECTION_EXECUTIONS.get());
        assertEquals(7, BehaviorLogReadGuardInterceptor.PROJECTION_ROWS.get(), "统计查询读到全部 7 行");
        assertEquals(7, BehaviorLogReadGuardInterceptor.DETAIL_ROWS.get(), "明细查询读到全部 7 行");
        assertEquals(1, BehaviorLogReadGuardInterceptor.DETAIL_EXECUTIONS.get());
        assertEquals(7, response.getTotal());
        assertEquals(7, response.getItems().size(), "正常场景（远低于上限）必须全量返回");
        assertEquals(7, response.getLowCount() + response.getMediumCount() + response.getHighCount());
    }

    // ==================== 造数与调用 ====================

    private BehaviorTimelineResponse timelineAsTeacher() {
        SecurityUtil.set(teacherLogin());
        try {
            return queryService.timeline(EXAM_ID, STUDENT_ID);
        } finally {
            SecurityUtil.clear();
        }
    }

    private void seedExam() {
        jdbc.update("DELETE FROM exam_behavior_logs WHERE exam_id = ?", EXAM_ID);
        jdbc.update("DELETE FROM exam_submissions WHERE exam_id = ?", EXAM_ID);
        jdbc.update("DELETE FROM exams WHERE id = ?", EXAM_ID);
        Timestamp start = Timestamp.valueOf(LocalDateTime.of(2026, 5, 1, 9, 0));
        Timestamp end = Timestamp.valueOf(LocalDateTime.of(2026, 5, 1, 11, 0));
        // published=0：本夹具只服务时间线；共享 H2 下学生考试列表按开始时间倒序只取第 1 页，
        // 发布态夹具会挤占该窗口并波及其他用例（与 MonitorOverviewProjectionGuardTest 同口径）。
        jdbc.update("INSERT INTO exams (id, title, paper_id, start_time, end_time, duration_minutes,"
                        + " status, published, created_by, version, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,60,?,0,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                EXAM_ID, "护栏-行为时间线", 980_400_900L, start, end, Exam.STATUS_IN_PROGRESS, OWNER_ID);
    }

    /**
     * 插入 n 行：event_time = base + i 分钟（i 从 0 起，严格递增），severity 按 i%3 轮转（i=0 → low），
     * event_type 每 2 行取未注册类型（兜底策略照常落库），event_data 带可识别 payload。
     */
    private int seedLogs(LocalDateTime base, int n) {
        List<Object[]> batch = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int severity = i % 3 + 1;
            String eventType = i % 2 == 0 ? UNKNOWN_TYPE_PREFIX + (i % 9) : "SWITCH_SCREEN";
            String eventData = "{\"payload\":\"row-" + i + "\"}";
            batch.add(new Object[] {EXAM_ID, STUDENT_ID, eventType, eventData, severity,
                    Timestamp.valueOf(base.plusMinutes(i))});
        }
        int[] affected = jdbc.batchUpdate("INSERT INTO exam_behavior_logs"
                + " (exam_id, student_id, event_type, event_data, severity, event_time, created_time)"
                + " VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP)", batch);
        return affected.length;
    }

    private int count(String sql, Object... args) {
        Number n = jdbc.queryForObject(sql, Number.class, args);
        return n == null ? -1 : n.intValue();
    }

    private static LoginUser teacherLogin() {
        LoginUser user = new LoginUser();
        user.setId(OWNER_ID);
        user.setRoleLevel(RoleHierarchy.levelOf(RoleHierarchy.TEACHER));
        return user;
    }

    /**
     * 时间线取数观测护栏（Executor 层，仅本测试上下文注册）：分别记录
     * {@code ExamBehaviorLogMapper.selectSeverities}（统计窄投影）与 {@code selectList}（明细）
     * 的执行次数、SQL 文本与返回行数。统计查询被合并回明细查询、上限被移除、
     * 或投影放宽为整行读都会让断言变红。
     */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                            org.apache.ibatis.cache.CacheKey.class, org.apache.ibatis.mapping.BoundSql.class})
    })
    static class BehaviorLogReadGuardInterceptor implements Interceptor {

        static final String PROJECTION_MS =
                "com.exam.submission.mapper.ExamBehaviorLogMapper.selectSeverities";
        static final String DETAIL_MS =
                "com.exam.submission.mapper.ExamBehaviorLogMapper.selectList";
        static final AtomicInteger PROJECTION_EXECUTIONS = new AtomicInteger();
        static final AtomicInteger PROJECTION_ROWS = new AtomicInteger();
        static final AtomicInteger DETAIL_EXECUTIONS = new AtomicInteger();
        static final AtomicInteger DETAIL_ROWS = new AtomicInteger();
        static volatile String projectionSql;
        static volatile String detailSql;

        static void reset() {
            PROJECTION_EXECUTIONS.set(0);
            PROJECTION_ROWS.set(0);
            DETAIL_EXECUTIONS.set(0);
            DETAIL_ROWS.set(0);
            projectionSql = null;
            detailSql = null;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
            String id = ms.getId();
            boolean projection = PROJECTION_MS.equals(id);
            boolean detail = DETAIL_MS.equals(id);
            if (projection) {
                projectionSql = ms.getBoundSql(invocation.getArgs()[1]).getSql();
            } else if (detail) {
                detailSql = ms.getBoundSql(invocation.getArgs()[1]).getSql();
            }
            Object result = invocation.proceed();
            if (result instanceof List<?> rows) {
                if (projection) {
                    PROJECTION_EXECUTIONS.incrementAndGet();
                    PROJECTION_ROWS.addAndGet(rows.size());
                } else if (detail) {
                    DETAIL_EXECUTIONS.incrementAndGet();
                    DETAIL_ROWS.addAndGet(rows.size());
                }
            }
            return result;
        }
    }
}
