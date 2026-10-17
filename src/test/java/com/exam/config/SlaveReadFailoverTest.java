package com.exam.config;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamSnapshotMapper;
import com.exam.exam.mapper.ExamMapper;
import com.exam.exam.service.ExamSnapshotService;
import com.exam.auth.security.LoginUser;
import com.exam.auth.security.SecurityUtil;
import com.exam.common.cache.CacheMutexLoader;
import com.exam.grading.dto.GradingProgressResponse;
import com.exam.grading.entity.GradingSubmission;
import com.exam.grading.entity.SubjectiveGrade;
import com.exam.grading.mapper.GradingSubmissionMapper;
import com.exam.grading.mapper.SubjectiveGradeMapper;
import com.exam.grading.service.GradingQueryService;
import com.exam.monitoring.metrics.BusinessMetrics;
import com.exam.paper.mapper.PaperSnapshotMapper;
import com.exam.paper.service.PaperService;
import com.exam.paper.service.PaperSnapshotService;
import com.exam.paper.entity.Paper;
import com.exam.question.mapper.QuestionMapper;
import com.exam.question.service.QuestionService;
import com.exam.exam.service.ExamPaperLockService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 从库读降级回主库单测（harden-slave-read-failover，B.2 中危 M3）：
 * <ul>
 *   <li>执行器机制：DataAccessException → 压栈 master 重试一次（重试期间栈顶=master，
 *       与 ReadYourWriteRouter 同机制）+ ERROR + 计数；主库也失败原样上抛；业务异常不重试不计数；
 *       正常路径零计数零压栈；</li>
 *   <li>三个读点接线：ExamSnapshotService（拉卷主链路）/ PaperSnapshotService /
 *       GradingQueryService 的从库读均经执行器包裹，从库故障时主库兜底可用。</li>
 * </ul>
 * 单测不启 Spring 代理（@DS/@Cacheable 切面不生效），因此断言的栈顶变化只来自执行器自身的
 * push/poll——恰好隔离证明「重试期间路由到 master」这一行为。
 */
class SlaveReadFailoverTest {

    private BusinessMetrics businessMetrics;
    private SlaveReadFallbackExecutor executor;

    /** 记录每次读调用时线程的路由栈顶（null=空栈） */
    private final List<Object> peekTrace = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        businessMetrics = mock(BusinessMetrics.class);
        executor = new SlaveReadFallbackExecutor(businessMetrics);
        // grading 接线用例的 lambdaQuery 依赖 MyBatis-Plus 实体元数据缓存（GradingQueryServiceProjectionTest 同款先例）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.exam.grading.entity.GradingSubmission.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.exam.grading.entity.SubjectiveGrade.class);
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
        // 防御：单测结束时线程栈必须已平衡（执行器 finally poll 的间接证据）
        assertNull(DynamicDataSourceContextHolder.peek(), "执行器结束后路由栈必须平衡");
    }

    // ==================== 执行器机制 ====================

    @Test
    void failoverPushesMasterAndReturnsRetryValue() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<String> read = () -> {
            peekTrace.add(DynamicDataSourceContextHolder.peek());
            if (calls.incrementAndGet() == 1) {
                throw new DataAccessResourceFailureException("slave down");
            }
            return "from-master";
        };

        assertEquals("from-master", executor.execute("exam_snapshot", read));
        assertEquals(2, calls.get(), "从库失败后必须恰好重试一次");
        assertNull(peekTrace.get(0), "首次调用不压栈（保持 @DS 原路由）");
        assertEquals("master", peekTrace.get(1), "重试必须压栈 master（与 ReadYourWriteRouter 同机制）");
        verify(businessMetrics).countDatasourceDegraded("exam_snapshot");
    }

    @Test
    void masterFailurePropagatesHonestly() {
        Supplier<String> read = () -> {
            throw new DataAccessResourceFailureException("db down");
        };

        assertThrows(DataAccessResourceFailureException.class, () -> executor.execute("exam_snapshot", read),
                "主库也失败必须原样上抛（诚实失败，不吞）");
        verify(businessMetrics, times(1)).countDatasourceDegraded("exam_snapshot");
    }

    @Test
    void businessExceptionNotCaughtAndNotCounted() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<String> read = () -> {
            calls.incrementAndGet();
            throw new BusinessException(ResponseCode.NOT_FOUND, "考试不存在");
        };

        BusinessException ex = assertThrows(BusinessException.class,
                () -> executor.execute("grading_progress", read));
        assertEquals(ResponseCode.NOT_FOUND.getCode(), ex.getCode());
        assertEquals(1, calls.get(), "业务异常不走降级口（不重试）");
        verify(businessMetrics, never()).countDatasourceDegraded(anyString());
    }

    @Test
    void normalPathNoMetricsNoPush() {
        assertEquals("ok", executor.execute("paper_snapshot", () -> "ok"));
        verify(businessMetrics, never()).countDatasourceDegraded(anyString());
    }

    // ==================== 读点接线：ExamSnapshotService（拉卷主链路） ====================

    @Test
    void examSnapshotCurrentFailsOverToMaster() {
        ExamSnapshotMapper snapshotMapper = mock(ExamSnapshotMapper.class);
        CacheMutexLoader mutexLoader = mock(CacheMutexLoader.class);
        SlaveReadFallbackExecutor realExecutor = new SlaveReadFallbackExecutor(businessMetrics);
        ExamSnapshotService service = new ExamSnapshotService(snapshotMapper,
                mock(com.exam.paper.mapper.PaperMapper.class), mock(com.exam.paper.mapper.PaperQuestionMapper.class),
                mock(QuestionMapper.class), mock(com.exam.paper.service.PaperSnapshotService.class),
                mutexLoader, new ObjectMapper(), realExecutor);
        // mock 互斥器直接执行回源 lambda（绕开 Redis，聚焦从库降级语义）
        when(mutexLoader.load(anyString(), any(), any())).thenAnswer(
                inv -> ((Supplier<?>) inv.getArgument(2)).get());
        AtomicInteger calls = new AtomicInteger();
        when(snapshotMapper.selectOne(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new DataAccessResourceFailureException("slave down");
            }
            return null; // 主库上也没有快照 → 业务 NOT_FOUND 原样传播（接线 + 重试已得证）
        });

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getCurrent(1L));
        assertEquals(ResponseCode.NOT_FOUND.getCode(), ex.getCode(), "业务异常原样传播（重试后主库仍查无）");
        assertEquals(2, calls.get(), "从库失败后经执行器在主库重试一次");
        verify(businessMetrics).countDatasourceDegraded("exam_snapshot");
    }

    // ==================== 读点接线：PaperSnapshotService ====================

    @Test
    void paperSnapshotCurrentFailsOverToMaster() {
        PaperService paperService = mock(PaperService.class);
        PaperSnapshotMapper snapshotMapper = mock(PaperSnapshotMapper.class);
        CacheMutexLoader mutexLoader = mock(CacheMutexLoader.class);
        SlaveReadFallbackExecutor realExecutor = new SlaveReadFallbackExecutor(businessMetrics);
        PaperSnapshotService service = new PaperSnapshotService(paperService,
                mock(com.exam.paper.mapper.PaperMapper.class), mock(com.exam.paper.mapper.PaperQuestionMapper.class),
                snapshotMapper, mock(QuestionMapper.class), mock(QuestionService.class),
                mock(ExamPaperLockService.class), mutexLoader, new ObjectMapper(), realExecutor);
        when(mutexLoader.load(anyString(), anyString(), any())).thenAnswer(
                inv -> ((Supplier<?>) inv.getArgument(2)).get());
        AtomicInteger calls = new AtomicInteger();
        when(paperService.getOwnedPaper(7L)).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new DataAccessResourceFailureException("slave down");
            }
            Paper paper = new Paper();
            paper.setId(7L);
            paper.setSnapshotId(null); // 主库上快照未生成 → 业务 NOT_FOUND 原样传播
            return paper;
        });

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getCurrent(7L));
        assertEquals(ResponseCode.NOT_FOUND.getCode(), ex.getCode());
        assertEquals(2, calls.get(), "归属校验读失败也经执行器在主库重试一次");
        verify(businessMetrics).countDatasourceDegraded("paper_snapshot");
    }

    // ==================== 读点接线：GradingQueryService（主库兜底成功路径） ====================

    @Test
    void gradingProgressSucceedsViaMasterAfterSlaveFailure() {
        ExamMapper examMapper = mock(ExamMapper.class);
        GradingSubmissionMapper submissionMapper = mock(GradingSubmissionMapper.class);
        SubjectiveGradeMapper subjectiveMapper = mock(SubjectiveGradeMapper.class);
        GradingQueryService service = new GradingQueryService(examMapper, submissionMapper,
                subjectiveMapper, executor);

        LoginUser teacher = new LoginUser();
        teacher.setId(9L);
        teacher.setRoleLevel(2);
        SecurityUtil.set(teacher);
        Exam exam = new Exam();
        exam.setId(1L);
        exam.setCreatedBy(9L);
        when(examMapper.selectById(1L)).thenReturn(exam);

        AtomicInteger selectCalls = new AtomicInteger();
        when(submissionMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
            peekTrace.add(DynamicDataSourceContextHolder.peek());
            if (selectCalls.incrementAndGet() == 1) {
                throw new DataAccessResourceFailureException("slave down");
            }
            return List.<GradingSubmission>of();
        });
        when(subjectiveMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.<SubjectiveGrade>of());

        GradingProgressResponse resp = service.progress(1L);

        assertNotNull(resp, "从库故障后主库兜底读必须成功返回进度总览");
        assertEquals(0, resp.getSubmittedCount());
        assertNull(peekTrace.get(0), "首次读在 @DS 原路由（单测无切面即空栈）");
        assertEquals("master", peekTrace.get(1), "重试读压栈 master");
        verify(businessMetrics).countDatasourceDegraded("grading_progress");
    }
}
