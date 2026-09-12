package com.exam.submission.service;

import com.exam.submission.dto.SubmitMessage;
import com.exam.submission.entity.ExamSubmission;
import com.exam.submission.mapper.ExamSubmissionMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 答卷持久化服务：交卷状态 CAS 与答案批量落库（MQ 消费者专用）。
 *
 * <p>批量落库说明（spec「消息可靠落库」场景）：手工开启 BATCH 执行器的 SqlSession，
 * 把一批交卷消息的 casFillAnswers 合并为 JDBC 批量写——MySQL 连接串开启
 * rewriteBatchedStatements 后会重写为多值语句，削峰场景显著降低往返次数；
 * casFillAnswers 仅在 answers 为 NULL 时写入，重复投递影响 0 行（消费端幂等）。
 */
@Slf4j
@Service
public class ExamSubmissionService {

    private final ExamSubmissionMapper submissionMapper;
    private final SqlSessionFactory sqlSessionFactory;

    public ExamSubmissionService(ExamSubmissionMapper submissionMapper, SqlSessionFactory sqlSessionFactory) {
        this.submissionMapper = submissionMapper;
        this.sqlSessionFactory = sqlSessionFactory;
    }

    /**
     * 按 (考试, 学生) 定位答卷。
     *
     * <p>读写分离（add-performance-deepening task3）：答卷详情是<b>强一致读</b>——
     * 刚交卷/兜底收卷后立即回读必须是最新状态，主从复制滞后会导致"查不到刚交的答卷"，
     * 因此<b>不加 {@code @DS("slave")}</b>、默认走主库（primary=master）。
     */
    public ExamSubmission getByExamStudent(Long examId, Long studentId) {
        return submissionMapper.selectByExamStudent(examId, studentId);
    }

    /**
     * 答卷状态机 CAS：进行中 → 已交卷（spec「三路竞态仅一次」场景）。
     *
     * @return true=本次迁移成功（唯一赢家）；false=已被并发路径迁移（幂等让位）
     */
    public boolean casSubmitToSubmitted(Long submissionId, LocalDateTime submitTime, int submitType) {
        return submissionMapper.casSubmit(submissionId, ExamSubmission.STATUS_IN_PROGRESS,
                ExamSubmission.STATUS_SUBMITTED, submitTime, submitType) > 0;
    }

    /**
     * 批量答案落库：一批消息合并为 JDBC batch，返回落库/幂等跳过条数。
     * 任一语句失败则整批回滚并抛出（调用方降级为逐条处理 + 重试/死信）。
     */
    public FillStats fillAnswersBatch(List<SubmitMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return new FillStats(0, 0);
        }
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            try {
                ExamSubmissionMapper mapper = session.getMapper(ExamSubmissionMapper.class);
                for (SubmitMessage message : messages) {
                    mapper.casFillAnswers(message.getSubmissionId(), message.getAnswers());
                }
                List<BatchResult> results = session.flushStatements();
                session.commit();
                int filled = 0;
                for (BatchResult result : results) {
                    for (int count : result.getUpdateCounts()) {
                        filled += Math.max(count, 0);
                    }
                }
                return new FillStats(filled, messages.size() - filled);
            } catch (RuntimeException e) {
                session.rollback();
                throw e;
            }
        }
    }

    /** 批量落库结果统计：filled=本次真实写入，skipped=幂等跳过（重复投递）。 */
    public record FillStats(int filled, int skipped) {
    }
}
