package com.exam.taking;

import com.exam.exam.entity.Exam;
import com.exam.submission.entity.ExamSubmission;
import com.exam.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「我的考试」列表取数范围与排序护栏（change fix-my-exams-list-scope 阶段 2）：
 *
 * <p>口径（阶段 1 判定，见 spec/changes/fix-my-exams-list-scope/evidence/scope-judgment.md）：
 * 列表 = 已发布 ∧ 未软删 ∧ (本人有答卷 ∪ 本人当前班级绑定的非补考考试 ∪ 本人为候选人的补考)；
 * 排序按 §12.1——分组优先级 待考→进行中→已完成，组内按开始时间距当前时刻近→远；
 * 上限固定（现 50）只作用于「我的考试」集合内。
 *
 * <p>夹具自析构（@AfterEach 清本测试固定 ID 段与自建班级）：共享 H2 下发布态夹具会挤占
 * 其余用例的读取窗口，本类不许把副作用留给别的用例（spec/README 遗留 #20 的互动机制）。
 */
@TestPropertySource(properties = {
        "exam.schedule.initial-delay-ms=3600000",
        "exam.schedule.fixed-delay-ms=3600000"
})
@DisplayName("我的考试列表：范围=本人归属（非全局窗口）且按 §12.1 排序")
class MyExamsListScopeIntegrationTest extends IntegrationTestBase {

    private static final long ID_LOW = 980_400_000L;
    private static final long ID_HIGH = 980_400_999L;
    private static final long OWNER_ID = 980_400_999L;
    private static final long PAPER_DUMMY = 980_400_900L;

    // 用例 1：范围
    private static final long EXAM_MINE_OLD = 980_400_001L;
    private static final long EXAM_MINE_UPCOMING = 980_400_002L;
    private static final long EXAM_MINE_SUBMITTED = 980_400_003L;
    private static final long EXAM_MINE_MAKEUP = 980_400_004L;
    private static final long DECOY_BASE = 980_400_100L;
    private static final int DECOY_COUNT = 55;

    // 用例 2：排序
    private static final long E_UP_NEAR = 980_400_201L;
    private static final long E_UP_FAR = 980_400_202L;
    private static final long E_ON_RECENT = 980_400_203L;
    private static final long E_ON_OLD = 980_400_204L;
    private static final long E_FIN_RECENT = 980_400_205L;
    private static final long E_FIN_OLD = 980_400_206L;

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
    }

    private final List<Long> createdClasses = new ArrayList<>();

    @Test
    @DisplayName("系统已发布考试超上限时，本人较早考试仍在列表；无关考试不进入")
    void myEarlierExamStaysVisibleWhenSystemPublishedExamsExceedCap() throws Exception {
        String teacher = registerTeacher();
        String s1 = registerStudent();
        String s2 = registerStudent();
        long s1Id = studentIdOf(s1);
        long s2Id = studentIdOf(s2);
        long classId = createClass(teacher, s1Id, s2Id);

        LocalDateTime now = LocalDateTime.now();
        Timestamp nowTs = Timestamp.valueOf(now);
        // 本人的较早考试：班级指派（本用例的缺陷主角——现实现下会被 55 场无关考试挤出全局窗口）
        insertExam(EXAM_MINE_OLD, "护栏-我的较早考试", classId, null,
                Timestamp.valueOf(now.minusDays(30)), Timestamp.valueOf(now.minusDays(30).plusHours(2)),
                Exam.STATUS_ENDED);
        // 班级指派、未开始
        insertExam(EXAM_MINE_UPCOMING, "护栏-我的待考", classId, null,
                Timestamp.valueOf(now.plusHours(3)), Timestamp.valueOf(now.plusHours(5)),
                Exam.STATUS_NOT_STARTED);
        // 无班级绑定，本人有答卷（「答过的」渠道）
        insertExam(EXAM_MINE_SUBMITTED, "护栏-我答过的", null, null,
                Timestamp.valueOf(now.minusDays(40)), Timestamp.valueOf(now.minusDays(40).plusHours(2)),
                Exam.STATUS_ENDED);
        insertSubmission(EXAM_MINE_SUBMITTED, s1Id, ExamSubmission.STATUS_SUBMITTED,
                nowTs, Timestamp.valueOf(now.minusDays(40).plusHours(1)));
        // 补考：本人为名单内候选人（「名单指派」渠道）；对 s2 为非候选
        insertExam(EXAM_MINE_MAKEUP, "护栏-我的补考", null, EXAM_MINE_OLD,
                Timestamp.valueOf(now.minusMinutes(10)), Timestamp.valueOf(now.plusHours(2)),
                Exam.STATUS_IN_PROGRESS);
        insertCandidate(EXAM_MINE_MAKEUP, s1Id);
        // 55 场无关的已发布考试（无班级、无归属）：现实现下它们占满全局窗口第 1 页 50 条
        for (int i = 0; i < DECOY_COUNT; i++) {
            insertExam(DECOY_BASE + i, "护栏-无关考试-" + i, null, null,
                    Timestamp.valueOf(now.plusDays(2).plusHours(i)),
                    Timestamp.valueOf(now.plusDays(2).plusHours(i).plusHours(1)),
                    Exam.STATUS_NOT_STARTED);
        }

        JsonNode list = myExams(s1);
        List<Long> ids = idsOf(list);
        assertTrue(ids.contains(EXAM_MINE_OLD),
                "本人的较早考试必须仍在列表（缺陷：全局窗口取数把它挤出了第 1 页 50 条）；实际=" + ids);
        assertTrue(ids.contains(EXAM_MINE_UPCOMING), "班级指派的待考考试必须可见；实际=" + ids);
        assertTrue(ids.contains(EXAM_MINE_SUBMITTED), "有答卷的考试必须可见（答过渠道）；实际=" + ids);
        assertTrue(ids.contains(EXAM_MINE_MAKEUP), "名单内候选人的补考必须可见；实际=" + ids);
        assertTrue(ids.stream().noneMatch(id -> id >= DECOY_BASE && id < DECOY_BASE + DECOY_COUNT),
                "无归属关系的考试不得进入我的列表（不是全局窗口）；实际=" + ids);
        assertEquals("FINISHED", groupOf(list, EXAM_MINE_OLD), "较早的已结束考试归已完成组");

        // 名单外同学：班级指派的可见，补考/无关考试/他人答卷不可见
        JsonNode list2 = myExams(s2);
        List<Long> ids2 = idsOf(list2);
        assertTrue(ids2.contains(EXAM_MINE_OLD), "同班同学的班级指派考试可见；实际=" + ids2);
        assertFalse(ids2.contains(EXAM_MINE_MAKEUP),
                "补考仅对名单内学生展示（非候选同学不可见）；实际=" + ids2);
        assertFalse(ids2.contains(EXAM_MINE_SUBMITTED),
                "本人无答卷且无班级绑定的考试不可见；实际=" + ids2);
        assertTrue(ids2.stream().noneMatch(id -> id >= DECOY_BASE && id < DECOY_BASE + DECOY_COUNT),
                "无归属关系的考试不得进入任何学生的列表；实际=" + ids2);
    }

    @Test
    @DisplayName("列表按分组优先级（待考→进行中→已完成）与组内近→远排序")
    void listOrdersByGroupPriorityThenProximity() throws Exception {
        String teacher = registerTeacher();
        String student = registerStudent();
        long studentId = studentIdOf(student);
        long classId = createClass(teacher, studentId);

        LocalDateTime now = LocalDateTime.now();
        // 待考组：先考的先显示（NOW 距离近→远）
        insertExam(E_UP_FAR, "护栏-待考-远", classId, null,
                Timestamp.valueOf(now.plusHours(26)), Timestamp.valueOf(now.plusHours(28)),
                Exam.STATUS_NOT_STARTED);
        insertExam(E_UP_NEAR, "护栏-待考-近", classId, null,
                Timestamp.valueOf(now.plusHours(2)), Timestamp.valueOf(now.plusHours(4)),
                Exam.STATUS_NOT_STARTED);
        // 进行中组：最近开始的先显示
        insertExam(E_ON_OLD, "护栏-进行中-早", classId, null,
                Timestamp.valueOf(now.minusMinutes(30)), Timestamp.valueOf(now.plusHours(2)),
                Exam.STATUS_IN_PROGRESS);
        insertSubmission(E_ON_OLD, studentId, ExamSubmission.STATUS_IN_PROGRESS,
                Timestamp.valueOf(now.minusMinutes(30)), Timestamp.valueOf(now.plusMinutes(30)));
        insertExam(E_ON_RECENT, "护栏-进行中-新", classId, null,
                Timestamp.valueOf(now.minusMinutes(5)), Timestamp.valueOf(now.plusHours(2)),
                Exam.STATUS_IN_PROGRESS);
        insertSubmission(E_ON_RECENT, studentId, ExamSubmission.STATUS_IN_PROGRESS,
                Timestamp.valueOf(now.minusMinutes(5)), Timestamp.valueOf(now.plusMinutes(55)));
        // 已完成组：最近开始的先显示
        insertExam(E_FIN_OLD, "护栏-已完成-久", classId, null,
                Timestamp.valueOf(now.minusDays(30)), Timestamp.valueOf(now.minusDays(30).plusHours(2)),
                Exam.STATUS_ENDED);
        insertExam(E_FIN_RECENT, "护栏-已完成-近", classId, null,
                Timestamp.valueOf(now.minusDays(1)), Timestamp.valueOf(now.minusDays(1).plusHours(2)),
                Exam.STATUS_ENDED);

        JsonNode list = myExams(student);
        assertEquals(List.of(E_UP_NEAR, E_UP_FAR, E_ON_RECENT, E_ON_OLD, E_FIN_RECENT, E_FIN_OLD),
                idsOf(list),
                "§12.1：待考→进行中→已完成；组内按开始时间距当前时刻近→远（待考先考的先显示，"
                        + "进行中/已完成最近开始的先显示）");
        assertEquals(List.of("UPCOMING", "UPCOMING", "ONGOING", "ONGOING", "FINISHED", "FINISHED"),
                list.findValues("group").stream().map(JsonNode::asText).toList(),
                "分组优先级：待考在前、已完成在后");
    }

    // ==================== 工具 ====================

    private JsonNode myExams(String token) throws Exception {
        return perform(jsonGet("/api/exam-taking/exams", token), 200).get("data");
    }

    private List<Long> idsOf(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(item -> ids.add(item.get("examId").asLong()));
        return ids;
    }

    private String groupOf(JsonNode list, long examId) {
        for (JsonNode item : list) {
            if (item.get("examId").asLong() == examId) {
                return item.get("group").asText();
            }
        }
        throw new AssertionError("列表缺少考试 " + examId);
    }

    /** 当前登录用户 ID（/api/auth/me）。 */
    private long studentIdOf(String token) throws Exception {
        return perform(jsonGet("/api/auth/me", token), 200).get("data").get("id").asLong();
    }

    /** 建班并把给定学生入班（真实指派渠道：user_class），返回班级 ID。 */
    private long createClass(String teacher, long... studentIds) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", unique("班"));
        long classId = perform(jsonPost("/api/classes", teacher,
                objectMapper.writeValueAsString(body)), 200).get("data").get("id").asLong();
        createdClasses.add(classId);
        for (long studentId : studentIds) {
            ObjectNode join = objectMapper.createObjectNode();
            join.put("userId", studentId);
            perform(jsonPost("/api/classes/" + classId + "/students", teacher,
                    objectMapper.writeValueAsString(join)), 200);
        }
        return classId;
    }

    private void insertExam(long id, String title, Long classId, Long parentExamId,
                            Timestamp start, Timestamp end, int status) {
        jdbc.update("INSERT INTO exams (id, title, paper_id, class_id, parent_exam_id, start_time,"
                        + " end_time, duration_minutes, allow_late_minutes, status, published, force_end,"
                        + " version, created_by, is_deleted, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,?,?,60,0,?,1,0,0,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, title, PAPER_DUMMY, classId, parentExamId, start, end, status, OWNER_ID);
    }

    private void insertSubmission(long examId, long studentId, int status,
                                  Timestamp start, Timestamp deadline) {
        jdbc.update("INSERT INTO exam_submissions (exam_id, student_id, start_time, deadline_time,"
                        + " status, version, created_time, updated_time)"
                        + " VALUES (?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                examId, studentId, start, deadline, status);
    }

    private void insertCandidate(long examId, long studentId) {
        jdbc.update("INSERT INTO exam_candidates (exam_id, student_id, created_time)"
                + " VALUES (?,?,CURRENT_TIMESTAMP)", examId, studentId);
    }
}
