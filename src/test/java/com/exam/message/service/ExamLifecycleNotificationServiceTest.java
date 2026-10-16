package com.exam.message.service;

import com.exam.clazz.service.ClassService;
import com.exam.exam.entity.Exam;
import com.exam.message.entity.UserMessage;
import com.exam.message.mapper.UserMessageMapper;
import com.exam.paper.entity.Paper;
import com.exam.paper.mapper.PaperMapper;
import com.exam.score.entity.ScoreReview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 考务生命周期通知批量投递单元测试（optimize-backend-batch-write-paths）：
 * <ul>
 *   <li>多收件人路径（考试发布 / 成绩发布）走 insertBatch 且每批 ≤500 行（去逐条 insert）；</li>
 *   <li>任一批失败被外层 try-catch 兜底降级——不抛出、不阻断主考务事务；</li>
 *   <li>单收件人路径（复核结果）保持单条 insert 不动。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ExamLifecycleNotificationServiceTest {

    @Mock
    private UserMessageMapper messageMapper;
    @Mock
    private ClassService classService;
    @Mock
    private PaperMapper paperMapper;

    @InjectMocks
    private ExamLifecycleNotificationService service;

    private Exam exam(Long id, Long classId) {
        Exam exam = new Exam();
        exam.setId(id);
        exam.setClassId(classId);
        exam.setPaperId(7L);
        exam.setStartTime(LocalDateTime.now().plusMinutes(30));
        return exam;
    }

    private void stubPaperTitle() {
        Paper paper = new Paper();
        paper.setTitle("试卷");
        when(paperMapper.selectById(7L)).thenReturn(paper);
    }

    private List<Long> students(int n) {
        List<Long> ids = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            ids.add(1000L + i);
        }
        return ids;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<List<UserMessage>> captureBatches(int expectedCalls) {
        ArgumentCaptor<List<UserMessage>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(messageMapper, times(expectedCalls)).insertBatch(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void examPublishedBatchesInChunksOfAtMost500() {
        stubPaperTitle();
        when(classService.listStudentIds(1L)).thenReturn(students(1200));

        service.notifyExamPublished(exam(9L, 1L));

        List<List<UserMessage>> batches = captureBatches(3);
        assertEquals(List.of(500, 500, 200),
                List.of(batches.get(0).size(), batches.get(1).size(), batches.get(2).size()),
                "每批 ≤500：1200 学生应分 500/500/200 三批");
        // 全部 1200 名收件人各落一条（无丢失、无重复），且逐条 insert 不再被调用
        assertEquals(1200, batches.stream().mapToInt(List::size).sum());
        verify(messageMapper, never()).insert(any(UserMessage.class));
    }

    @Test
    void scorePublishedAlsoBathesAndKeepsRecipients() {
        stubPaperTitle();
        service.notifyScorePublished(exam(9L, 1L), students(501));

        List<List<UserMessage>> batches = captureBatches(2);
        assertEquals(500, batches.get(0).size());
        assertEquals(1, batches.get(1).size());
        assertEquals(1001L, batches.get(0).get(0).getUserId());
        assertEquals(UserMessage.READ_FLAG_UNREAD, batches.get(0).get(0).getReadFlag());
        verify(messageMapper, never()).insert(any(UserMessage.class));
    }

    @Test
    void batchFailureDegradesSilentlyAndDoesNotPropagate() {
        stubPaperTitle();
        when(classService.listStudentIds(1L)).thenReturn(students(3));
        doThrow(new IllegalStateException("db down")).when(messageMapper).insertBatch(anyList());

        // 旁路降级：任一批失败被 try-catch 兜底，绝不抛出以阻断主事务
        assertDoesNotThrow(() -> service.notifyExamPublished(exam(9L, 1L)));
    }

    @Test
    void emptyRecipientsTouchNothing() {
        when(classService.listStudentIds(1L)).thenReturn(List.of());

        service.notifyExamPublished(exam(9L, 1L));

        verifyNoInteractions(messageMapper);
    }

    @Test
    void singleRecipientReviewHandledStillUsesSingleInsert() {
        stubPaperTitle();
        ScoreReview review = new ScoreReview();
        review.setId(3L);
        review.setStudentId(1001L);

        service.notifyScoreReviewHandled(review, exam(9L, 1L), "AGREE", new BigDecimal("5"));

        ArgumentCaptor<UserMessage> captor = ArgumentCaptor.forClass(UserMessage.class);
        verify(messageMapper).insert(captor.capture());
        assertEquals(1001L, captor.getValue().getUserId());
        assertEquals("成绩复核结果通知", captor.getValue().getTitle());
        verify(messageMapper, never()).insertBatch(anyList());
    }
}
