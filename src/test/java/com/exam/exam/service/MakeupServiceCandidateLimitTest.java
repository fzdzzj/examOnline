package com.exam.exam.service;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamAbsenceMapper;
import com.exam.exam.mapper.ExamCandidateMapper;
import com.exam.exam.mapper.ExamMapper;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 补考名单限制单测（spec「名单限制进入」场景，§12.5）：
 * 非补考考试不限制；补考名单外学生拒绝进入（403），名单内学生放行。
 */
@ExtendWith(MockitoExtension.class)
class MakeupServiceCandidateLimitTest {

    @Mock
    private ExamMapper examMapper;
    @Mock
    private ExamCandidateMapper candidateMapper;
    @Mock
    private ExamAbsenceMapper absenceMapper;
    @Mock
    private GradingSubmissionMapper gradingMapper;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private MakeupService service;

    private Exam exam(Long parentExamId) {
        Exam exam = new Exam();
        exam.setId(10L);
        exam.setParentExamId(parentExamId);
        return exam;
    }

    @Test
    void nonMakeupExamNotRestricted() {
        // 非补考（parent_exam_id=null）：不触碰名单，直接放行
        assertDoesNotThrow(() -> service.assertCanEnter(exam(null), 1L));
        verifyNoInteractions(candidateMapper);
    }

    @Test
    void outsideCandidateRejected() {
        // 补考名单外学生（count=0）：拒绝进入，FORBIDDEN 403
        when(candidateMapper.selectCount(any())).thenReturn(0L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.assertCanEnter(exam(5L), 99L));
        assertEquals(ResponseCode.FORBIDDEN.getCode(), e.getCode());
        assertEquals(403, e.getHttpStatus());
    }

    @Test
    void inCandidateAllowed() {
        // 名单内学生放行
        when(candidateMapper.selectCount(any())).thenReturn(1L);
        assertDoesNotThrow(() -> service.assertCanEnter(exam(5L), 1L));
        verifyNoInteractions(examMapper);
    }

    @Test
    void nullCountTreatedAsOutside() {
        when(candidateMapper.selectCount(any())).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.assertCanEnter(exam(5L), 1L));
    }
}