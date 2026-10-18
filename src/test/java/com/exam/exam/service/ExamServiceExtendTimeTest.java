package com.exam.exam.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.RoleHierarchy;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.dto.ExamTimeExtensionRequest;
import com.exam.exam.dto.ExamTimeExtensionResponse;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.message.service.ExamLifecycleNotificationService;
import com.exam.paper.mapper.PaperMapper;
import com.exam.paper.service.PaperService;
import com.exam.service.AuditLogService;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 考中应急顺延与个性化补时服务层单元契约（add-exam-time-extension）：
 * <ul>
 *   <li>全场模式：主表窗口顺延 + 在途答卷整体顺延（extendDeadline 传 null 名单）+ 通知全体在途考生；</li>
 *   <li>个人模式：主表零改动 + 仅指定考生在途答卷顺延 + 已交卷的指定考生不进通知名单；</li>
 *   <li>状态门控：非进行中一律 400，且三路副作用（主表/答卷/审计）零触发。</li>
 * </ul>
 * HTTP 语义（403/400/缓存失效/旁路降级落库）走真实链路，见 ExamTimeExtensionIntegrationTest。
 */
@ExtendWith(MockitoExtension.class)
class ExamServiceExtendTimeTest {

    private static final Long TEACHER_ID = 8L;
    private static final Long EXAM_ID = 5L;

    @Mock
    private ExamMapper examMapper;
    @Mock
    private PaperService paperService;
    @Mock
    private PaperMapper paperMapper;
    @Mock
    private ExamStateMachineService stateMachineService;
    @Mock
    private ExamSnapshotService snapshotService;
    @Mock
    private AbsenceService absenceService;
    @Mock
    private ExamSubmissionMapper submissionMapper;
    @Mock
    private ExamLifecycleNotificationService lifecycleNotificationService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ExamService examService;

    @BeforeAll
    static void initEntityTableInfo() {
        // 纯单测无 Spring 容器：显式初始化实体的 TableInfo，让服务内的 lambda Wrapper 能解析列名
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ExamSubmission.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Exam.class);
    }

    @BeforeEach
    void setUp() {
        SecurityUtil.set(teacherLogin());
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    private static LoginUser teacherLogin() {
        LoginUser user = new LoginUser();
        user.setId(TEACHER_ID);
        user.setRoleLevel(RoleHierarchy.levelOf(RoleHierarchy.TEACHER));
        return user;
    }

    private Exam exam(int status) {
        Exam exam = new Exam();
        exam.setId(EXAM_ID);
        exam.setTitle("顺延单测考试");
        exam.setCreatedBy(TEACHER_ID);
        exam.setStatus(status);
        exam.setEndTime(LocalDateTime.of(2026, 10, 18, 16, 0));
        exam.setDurationMinutes(60);
        return exam;
    }

    private ExamSubmission inflight(Long studentId) {
        ExamSubmission submission = new ExamSubmission();
        submission.setExamId(EXAM_ID);
        submission.setStudentId(studentId);
        submission.setStatus(ExamSubmission.STATUS_IN_PROGRESS);
        return submission;
    }

    private ExamTimeExtensionRequest request(int minutes, List<Long> studentIds, String reason) {
        ExamTimeExtensionRequest request = new ExamTimeExtensionRequest();
        request.setExtensionMinutes(minutes);
        request.setStudentIds(studentIds);
        request.setReason(reason);
        return request;
    }

    @Test
    void globalModeShiftsWindowAndNotifiesAllInflightStudents() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(Exam.STATUS_IN_PROGRESS));
        when(submissionMapper.selectList(any())).thenReturn(List.of(inflight(101L), inflight(102L)));
        when(submissionMapper.extendDeadline(EXAM_ID, 30, null)).thenReturn(2);

        ExamTimeExtensionResponse response = examService.extendTime(EXAM_ID, request(30, null, "机房突发断电应急顺延"));

        assertEquals(EXAM_ID, response.examId());
        assertEquals(30, response.extensionMinutes());
        assertEquals(true, response.isGlobal());
        assertEquals(2, response.affectedSubmissionsCount());
        assertEquals(LocalDateTime.of(2026, 10, 18, 16, 30), response.newEndTime());
        assertEquals(90, response.newDurationMinutes());

        verify(examMapper).update(isNull(), any());
        verify(submissionMapper).extendDeadline(EXAM_ID, 30, null);
        verify(auditLogService).logExamExtendTime(EXAM_ID, "顺延单测考试", 30, true,
                2, List.of(101L, 102L), "机房突发断电应急顺延");
        verify(lifecycleNotificationService).notifyExamTimeExtended(any(Exam.class),
                eq(List.of(101L, 102L)), eq(30), eq(true));
    }

    @Test
    void individualModeKeepsWindowAndExcludesSubmittedFromRecipients() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(Exam.STATUS_IN_PROGRESS));
        // 300 号已交卷（不在 selectList 返回里）→ 即使被指定也不收"截止已顺延"通知
        when(submissionMapper.selectList(any())).thenReturn(List.of(inflight(101L), inflight(102L)));
        when(submissionMapper.extendDeadline(EXAM_ID, 20, List.of(101L, 300L))).thenReturn(1);

        ExamTimeExtensionResponse response = examService.extendTime(EXAM_ID,
                request(20, List.of(101L, 300L), "考生设备死机重启补时"));

        assertEquals(false, response.isGlobal());
        assertEquals(1, response.affectedSubmissionsCount());
        assertEquals(LocalDateTime.of(2026, 10, 18, 16, 0), response.newEndTime(), "个人模式主表窗口不变");
        assertEquals(60, response.newDurationMinutes());

        verify(examMapper, never()).update(isNull(), any());
        verify(submissionMapper).extendDeadline(EXAM_ID, 20, List.of(101L, 300L));
        verify(auditLogService).logExamExtendTime(EXAM_ID, "顺延单测考试", 20, false,
                1, List.of(101L), "考生设备死机重启补时");
        verify(lifecycleNotificationService).notifyExamTimeExtended(any(Exam.class),
                eq(List.of(101L)), eq(20), eq(false));
    }

    @Test
    void rejectsNonInProgressExamWithoutAnySideEffect() {
        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(Exam.STATUS_NOT_STARTED));

        BusinessException notStarted = assertThrows(BusinessException.class,
                () -> examService.extendTime(EXAM_ID, request(30, null, "误操作")));
        assertEquals(ResponseCode.BAD_REQUEST.getCode(), notStarted.getCode());

        when(examMapper.selectById(EXAM_ID)).thenReturn(exam(Exam.STATUS_ENDED));
        BusinessException ended = assertThrows(BusinessException.class,
                () -> examService.extendTime(EXAM_ID, request(30, List.of(101L), "误操作")));
        assertEquals(ResponseCode.BAD_REQUEST.getCode(), ended.getCode());

        verify(submissionMapper, never()).extendDeadline(any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(examMapper, never()).update(isNull(), any());
        verify(auditLogService, never()).logExamExtendTime(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
        verify(lifecycleNotificationService, never()).notifyExamTimeExtended(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
