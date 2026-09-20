package com.exam.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 线程池隔离配置测试（add-thread-pool-isolation）
 */
@SpringBootTest
@ActiveProfiles("test")
public class ThreadPoolConfigTest {

    @Autowired(required = false)
    private ThreadPoolTaskExecutor submitExecutor;

    @Autowired(required = false)
    private ThreadPoolTaskExecutor gradeExecutor;

    @Autowired(required = false)
    private ThreadPoolTaskExecutor monitorExecutor;

    @Test
    public void testSubmitExecutorExists() {
        assertNotNull(submitExecutor, "submitExecutor should be created");
        assertEquals(10, submitExecutor.getCorePoolSize());
        assertEquals(50, submitExecutor.getMaxPoolSize());
        assertEquals(100, submitExecutor.getQueueCapacity());
        assertEquals("exam-submit-", submitExecutor.getThreadNamePrefix());
    }

    @Test
    public void testGradeExecutorExists() {
        assertNotNull(gradeExecutor, "gradeExecutor should be created");
        assertEquals(20, gradeExecutor.getCorePoolSize());
        assertEquals(100, gradeExecutor.getMaxPoolSize());
        assertEquals(500, gradeExecutor.getQueueCapacity());
        assertEquals("exam-grade-", gradeExecutor.getThreadNamePrefix());
    }

    @Test
    public void testMonitorExecutorExists() {
        assertNotNull(monitorExecutor, "monitorExecutor should be created");
        assertEquals(5, monitorExecutor.getCorePoolSize());
        assertEquals(20, monitorExecutor.getMaxPoolSize());
        assertEquals(50, monitorExecutor.getQueueCapacity());
        assertEquals("exam-monitor-", monitorExecutor.getThreadNamePrefix());
    }

    @Test
    public void testExecutorsAreDistinct() {
        assertNotSame(submitExecutor, gradeExecutor, "submit and grade executors should be different instances");
        assertNotSame(gradeExecutor, monitorExecutor, "grade and monitor executors should be different instances");
        assertNotSame(submitExecutor, monitorExecutor, "submit and monitor executors should be different instances");
    }
}
