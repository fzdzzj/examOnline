package com.exam.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamReservation;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.mapper.ExamReservationMapper;
import com.exam.message.entity.UserMessage;
import com.exam.message.mapper.UserMessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 开考提醒投递行级幂等单元测试（add-exam-reservation）：
 * 核心是 deliverIfAbsent 的判定——markRemindedIfNull 影响行数=1 才写消息（幂等闸）。
 * 覆盖：并发/重复扫描时影响行数=0 不写消息；无候选考试不扫描。
 * （提前量窗口的 SQL 侧筛选由集成测试 remindNotDeliveredOutsideWindow 用真实 DB 验证。）
 */
@ExtendWith(MockitoExtension.class)
class ReservationRemindServiceTest {

    @Mock
    private ExamMapper examMapper;
    @Mock
    private ExamReservationMapper reservationMapper;
    @Mock
    private UserMessageMapper messageMapper;

    @InjectMocks
    private ReservationRemindService service;

    private Exam exam(Long id, LocalDateTime start, int status, int published, int reservationRequired) {
        Exam exam = new Exam();
        exam.setId(id);
        exam.setStartTime(start);
        exam.setStatus(status);
        exam.setPublished(published);
        exam.setReservationRequired(reservationRequired);
        exam.setTitle("考试" + id);
        return exam;
    }

    private ExamReservation reservation(Long id, Long examId, Long studentId) {
        ExamReservation r = new ExamReservation();
        r.setId(id);
        r.setExamId(examId);
        r.setStudentId(studentId);
        return r;
    }

    @Test
    void deliverWritesMessageOnlyWhenMarkClaimed() {
        Exam e = exam(1L, LocalDateTime.now().plusMinutes(10), 0, 1, 1);
        when(examMapper.selectList(any()))
                .thenReturn(List.of(e));   // 候选考试：需预约∧已发布∧未开始∧窗内
        when(reservationMapper.selectList(any()))
                .thenReturn(List.of(reservation(10L, 1L, 1001L)));
        // 本行赢得投递权（影响行数=1）→ 写消息
        when(reservationMapper.markRemindedIfNull(eq(10L), any())).thenReturn(1);

        assertEquals(1, service.remind());

        ArgumentCaptor<UserMessage> captor = ArgumentCaptor.forClass(UserMessage.class);
        verify(messageMapper).insert(captor.capture());
        assertEquals(1001L, captor.getValue().getUserId());
        assertEquals(1L, captor.getValue().getExamId());
        assertEquals(UserMessage.READ_FLAG_UNREAD, captor.getValue().getReadFlag());
    }

    @Test
    void deliverSkipsWhenMarkNotClaimed() {
        // 模拟并发/重复扫描：另一实例已投递给同一行（影响行数=0）→ 本实例不得写消息
        Exam e = exam(1L, LocalDateTime.now().plusMinutes(10), 0, 1, 1);
        when(examMapper.selectList(any())).thenReturn(List.of(e));
        when(reservationMapper.selectList(any()))
                .thenReturn(List.of(reservation(10L, 1L, 1001L)));
        when(reservationMapper.markRemindedIfNull(eq(10L), any())).thenReturn(0);

        assertEquals(0, service.remind());
        verify(messageMapper, never()).insert(any(UserMessage.class));
    }

    @Test
    void noCandidateExamsNoScan() {
        // 扫描不到候选考试 → 不触碰预约表与消息表
        when(examMapper.selectList(any())).thenReturn(List.of());
        assertEquals(0, service.remind());
        verifyNoInteractions(reservationMapper, messageMapper);
    }

    @Test
    void pendingReservationsFetchedOnceAcrossExams() {
        // 去 N+1（optimize-backend-batch-write-paths）：三场候选考试也只批查一次预约行
        Exam e1 = exam(1L, LocalDateTime.now().plusMinutes(10), 0, 1, 1);
        Exam e2 = exam(2L, LocalDateTime.now().plusMinutes(12), 0, 1, 1);
        Exam e3 = exam(3L, LocalDateTime.now().plusMinutes(15), 0, 1, 1);
        when(examMapper.selectList(any())).thenReturn(List.of(e1, e2, e3));
        // 一次 IN(examIds) + reminded_time IS NULL 取回跨场预约行，内存按 examId 分组
        when(reservationMapper.selectList(any())).thenReturn(List.of(
                reservation(10L, 1L, 1001L),
                reservation(20L, 2L, 1002L),
                reservation(30L, 3L, 1003L)));
        when(reservationMapper.markRemindedIfNull(any(), any())).thenReturn(1);

        assertEquals(3, service.remind());

        // 无论几场候选考试，候选预约行只批查一次（旧实现逐场 = 3 次）
        verify(reservationMapper, times(1)).selectList(any());
        verify(messageMapper, times(3)).insert(any(UserMessage.class));
    }
}