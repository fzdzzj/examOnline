package com.exam.exam.service;

import com.exam.clazz.service.ClassService;
import com.exam.exam.entity.Exam;
import com.exam.exam.entity.ExamAbsence;
import com.exam.exam.mapper.ExamAbsenceMapper;
import com.exam.exam.mapper.ExamMapper;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import com.exam.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 缺考标记单元测试（spec「考试结束标记缺考」场景）：
 * 验证「应考名单 − 有答卷者 = 缺考」的差集推导，以及关键边界（未绑班级跳过、全员有答卷不写）。
 * 幂等由 INSERT IGNORE + uk_exam_student 唯一索引在 DB 层兜底，此处验证服务端正确构造差集
 * 并交给批量幂等写入。
 */
@ExtendWith(MockitoExtension.class)
class AbsenceServiceTest {

    @Mock
    private ExamAbsenceMapper absenceMapper;
    @Mock
    private ExamMapper examMapper;
    @Mock
    private ExamSubmissionMapper submissionMapper;
    @Mock
    private ClassService classService;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private AbsenceService service;

    private Exam exam(Long id, Long classId) {
        Exam exam = new Exam();
        exam.setId(id);
        exam.setClassId(classId);
        return exam;
    }

    @Test
    void markAbsenceComputesDiffBetweenExpectedAndSubmitted() {
        // 应考 = [1,2,3]，有答卷 = [2]，缺考应为 [1,3]
        when(examMapper.selectById(10L)).thenReturn(exam(10L, 100L));
        when(classService.listStudentIds(100L)).thenReturn(List.of(1L, 2L, 3L));
        when(submissionMapper.selectList(any()))
                .thenReturn(List.of(submission(10L, 2L)));
        when(absenceMapper.insertIgnoreBatch(any())).thenReturn(3);

        int inserted = service.markAbsence(10L);

        ArgumentCaptor<List<ExamAbsence>> captor = ArgumentCaptor.forClass(List.class);
        verify(absenceMapper).insertIgnoreBatch(captor.capture());
        List<ExamAbsence> absences = captor.getValue();
        assertEquals(2, absences.size());
        assertEquals(3, inserted);
        assertEquals(List.of(1L, 3L), absences.stream().map(ExamAbsence::getStudentId).toList());
        absences.forEach(a -> {
            assertEquals(10L, a.getExamId());
            assertEquals(ExamAbsence.STATUS_ABSENT, a.getStatus());
            assertEquals(a.getMarkedTime(), a.getCreatedTime());
        });
    }

    @Test
    void unboundExamSkipped() {
        // 未绑班级：无应考名单可言，不触碰 classService/absenceMapper
        when(examMapper.selectById(10L)).thenReturn(exam(10L, null));

        assertEquals(0, service.markAbsence(10L));
        verifyNoInteractions(classService, absenceMapper);
    }

    @Test
    void allExpectedSubmittedMarksNone() {
        // 应考 = [1,2]，所有学生均有答卷 → 差集为空，不写缺考
        when(examMapper.selectById(10L)).thenReturn(exam(10L, 100L));
        when(classService.listStudentIds(100L)).thenReturn(List.of(1L, 2L));
        when(submissionMapper.selectList(any()))
                .thenReturn(List.of(submission(10L, 1L), submission(10L, 2L)));

        assertEquals(0, service.markAbsence(10L));
        verify(absenceMapper, never()).insertIgnoreBatch(any());
    }

    private ExamSubmission submission(Long examId, Long studentId) {
        ExamSubmission s = new ExamSubmission();
        s.setExamId(examId);
        s.setStudentId(studentId);
        return s;
    }
}