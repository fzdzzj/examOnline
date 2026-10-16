package com.exam.exam.service;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import com.exam.config.CacheConfig;
import com.exam.exam.entity.Exam;
import com.exam.exam.mapper.ExamMapper;
import com.exam.monitoring.metrics.BusinessMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 考试状态机单元测试（spec「考试状态机」需求）：
 * 重点验证乐观锁 CAS 语义——并发流转同一考试仅一个成功，另一个影响 0 行必须报错而非静默成功；
 * 以及定时推进对已发布/进行中考试的筛选与批量迁移行为。
 */
@ExtendWith(MockitoExtension.class)
class ExamStateMachineServiceTest {

    @Mock
    private ExamMapper examMapper;

    @Mock
    private AbsenceService absenceService;

    @Mock
    private BusinessMetrics metrics;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    /**
     * 编程式事务用真实的 {@link TransactionTemplate} + 打桩的事务管理器：
     * 事务边界的真库事实由 {@code ExamStateMachineTransactionTest}（H2）负责，
     * 本纯单测只需让 {@code execute} 把回调跑起来。
     */
    @Mock
    private PlatformTransactionManager transactionManager;

    private ExamStateMachineService service;

    @BeforeEach
    void setUp() {
        // lenient：casTransition 各用例不经事务模板，严格模式会把这些用例判成多余打桩
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ExamStateMachineService(examMapper, absenceService, metrics, cacheManager,
                transactionManager);
    }

    private Exam exam(Long id, int status, int version) {
        Exam exam = new Exam();
        exam.setId(id);
        exam.setStatus(status);
        exam.setVersion(version);
        return exam;
    }

    @Test
    void casTransitionSuccessBumpsVersion() {
        when(examMapper.selectById(1L)).thenReturn(exam(1L, Exam.STATUS_NOT_STARTED, 3));
        when(examMapper.casUpdateStatus(1L, Exam.STATUS_NOT_STARTED, Exam.STATUS_IN_PROGRESS, 3))
                .thenReturn(1);

        Exam result = service.casTransition(1L, Exam.STATUS_NOT_STARTED, Exam.STATUS_IN_PROGRESS);

        assertEquals(Exam.STATUS_IN_PROGRESS, result.getStatus());
        assertEquals(4, result.getVersion());
    }

    @Test
    void casConflictWhenConcurrentTransitionWins() {
        // 模拟并发竞态：读取时 version=5，执行 CAS 前另一请求已完成迁移（version 已变），
        // 本次 UPDATE ... WHERE status=1 AND version=5 影响 0 行 → 必须抛状态冲突，不得静默成功
        when(examMapper.selectById(1L)).thenReturn(exam(1L, Exam.STATUS_IN_PROGRESS, 5));
        when(examMapper.casUpdateStatus(1L, Exam.STATUS_IN_PROGRESS, Exam.STATUS_ENDED, 5))
                .thenReturn(0);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.casTransition(1L, Exam.STATUS_IN_PROGRESS, Exam.STATUS_ENDED));

        assertEquals(ResponseCode.STATE_CONFLICT.getCode(), e.getCode());
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void illegalTransitionsRejectedBeforeTouchingDb() {
        // 跳级（未开始→已结束）与回退（已结束→进行中）均在进入 DB 前被拒绝
        assertThrows(BusinessException.class,
                () -> service.casTransition(1L, Exam.STATUS_NOT_STARTED, Exam.STATUS_ENDED));
        assertThrows(BusinessException.class,
                () -> service.casTransition(1L, Exam.STATUS_ENDED, Exam.STATUS_IN_PROGRESS));
        verifyNoInteractions(examMapper);
    }

    @Test
    void missingExamRejected() {
        when(examMapper.selectById(404L)).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.casTransition(404L, Exam.STATUS_IN_PROGRESS, Exam.STATUS_ENDED));
        assertEquals(ResponseCode.NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void autoAdvanceMovesPublishedToStartAndOngoingToEnd() {
        Exam toStart = exam(10L, Exam.STATUS_NOT_STARTED, 1);
        toStart.setPublished(1);
        toStart.setStartTime(LocalDateTime.now().minusMinutes(1));
        Exam toEnd = exam(20L, Exam.STATUS_IN_PROGRESS, 2);
        toEnd.setEndTime(LocalDateTime.now().minusMinutes(1));

        when(examMapper.selectList(any())).thenReturn(List.of(toStart)).thenReturn(List.of(toEnd));
        when(examMapper.casUpdateStatus(10L, Exam.STATUS_NOT_STARTED, Exam.STATUS_IN_PROGRESS, 1))
                .thenReturn(1);
        when(examMapper.casUpdateStatus(20L, Exam.STATUS_IN_PROGRESS, Exam.STATUS_ENDED, 2))
                .thenReturn(1);
        when(cacheManager.getCache(CacheConfig.CACHE_EXAM_DETAIL)).thenReturn(cache);

        assertEquals(2, service.autoAdvance());
        // 进行中→已结束 迁移成功即触发缺考标记（缺考锚定"时间窗彻底关闭"这一刻，§8.10）
        verify(absenceService).markAbsence(20L);
        // H-04（harden-core-security-and-state-boundaries）：moved > 0 → 主动清空 examDetail 缓存
        verify(cacheManager).getCache(CacheConfig.CACHE_EXAM_DETAIL);
        verify(cache).clear();
    }

    @Test
    void autoAdvanceSkipsConflictsAndKeepsBatchGoing() {
        // 批量推进中某一场 CAS 影响 0 行（并发已迁移）只跳过，不抛错、不影响同批其他考试
        Exam stale = exam(30L, Exam.STATUS_IN_PROGRESS, 1);
        stale.setEndTime(LocalDateTime.now().minusMinutes(1));

        when(examMapper.selectList(any())).thenReturn(List.of()).thenReturn(List.of(stale));
        when(examMapper.casUpdateStatus(30L, Exam.STATUS_IN_PROGRESS, Exam.STATUS_ENDED, 1))
                .thenReturn(0);

        assertEquals(0, service.autoAdvance());
        // H-04：本轮无迁移（moved == 0）→ 不触碰缓存
        verify(cacheManager, never()).getCache(any());
    }

    @Test
    void forceEndOnlyLegalFromInProgress() {
        // 提前结束只允许 进行中→已结束；未开始/已结束调用方会先收到非法迁移拦截
        assertThrows(BusinessException.class,
                () -> service.casTransition(1L, Exam.STATUS_NOT_STARTED, Exam.STATUS_ENDED));
        verify(examMapper, never()).casUpdateStatus(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }
}
