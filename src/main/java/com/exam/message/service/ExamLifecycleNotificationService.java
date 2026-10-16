package com.exam.message.service;

import com.exam.clazz.service.ClassService;
import com.exam.exam.entity.Exam;
import com.exam.message.entity.UserMessage;
import com.exam.message.mapper.UserMessageMapper;
import com.exam.paper.entity.Paper;
import com.exam.paper.mapper.PaperMapper;
import com.exam.score.entity.ScoreReview;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 考务生命周期旁路通知服务（add-exam-lifecycle-notifications）：
 *
 * <p>在考试发布、成绩发布、成绩复核处理三个关键考务节点向相关学生投递站内消息
 * （user_messages 表，消息中心挂件消费）：
 * <ul>
 *   <li>{@link #notifyExamPublished}：向考试关联班级（exam.classId）全体学生投递【考试发布通知】；</li>
 *   <li>{@link #notifyScorePublished}：向该场考试实考考生（由调用方从已批改答卷推导）投递【成绩发布通知】；</li>
 *   <li>{@link #notifyScoreReviewHandled}：向复核申诉学生投递【成绩复核结果通知】。</li>
 * </ul>
 *
 * <p><b>旁路降级（硬约定）</b>：三个方法统一 try-catch 捕获任何异常并记录 warn 日志后静默返回，
 * 通知写入失败绝不影响或回滚考试发布 / 成绩发布 / 复核处理的主考务事务——
 * 通知是增强而非主链路的一部分，不能被它拖垮考务主业务。
 */
@Slf4j
@Service
public class ExamLifecycleNotificationService {

    /**
     * 批量插入批大小（optimize-backend-batch-write-paths）：与
     * harden-review-flow-and-resource-limits 的 500 分片先例同量级，
     * 防单语句超 {@code max_allowed_packet}。
     */
    static final int INSERT_BATCH_SIZE = 500;

    private final UserMessageMapper messageMapper;
    private final ClassService classService;
    private final PaperMapper paperMapper;

    public ExamLifecycleNotificationService(UserMessageMapper messageMapper,
                                            ClassService classService,
                                            PaperMapper paperMapper) {
        this.messageMapper = messageMapper;
        this.classService = classService;
        this.paperMapper = paperMapper;
    }

    /**
     * 考试发布通知：向考试关联班级（exam.classId）全体学生各投一条「考试发布通知」。
     * content 包含试卷标题、开考时间与考试 ID。
     */
    public void notifyExamPublished(Exam exam) {
        try {
            if (exam.getClassId() == null) {
                return;
            }
            List<Long> studentIds = classService.listStudentIds(exam.getClassId());
            if (studentIds.isEmpty()) {
                return;
            }
            String content = "您所在班级的考试《" + paperTitle(exam) + "》已发布，开考时间为 "
                    + formatStart(exam) + "，请做好准备。考试ID：" + exam.getId();
            List<UserMessage> messages = new ArrayList<>(studentIds.size());
            for (Long studentId : studentIds) {
                messages.add(buildMessage(studentId, exam.getId(),
                        "考试发布通知", content));
            }
            insertInBatches(messages);
            log.info("考试发布通知投递 {} 条：exam={}", studentIds.size(), exam.getId());
        } catch (Exception e) {
            // 旁路降级：通知插入失败绝不阻断考试发布主事务
            log.warn("考试发布通知投递失败（旁路降级，不影响发布）：exam={} err={}",
                    exam.getId(), e.toString());
        }
    }

    /**
     * 成绩发布通知：向该场考试所有实考考生（调用方传入，源自 status=GRADED 答卷）各投一条。
     * content 包含试卷标题、查分提示与考试 ID。
     */
    public void notifyScorePublished(Exam exam, List<Long> studentIds) {
        try {
            if (studentIds == null || studentIds.isEmpty()) {
                return;
            }
            String content = "您参加的考试《" + paperTitle(exam) + "》成绩已发布，请在成绩查询中查看。考试ID："
                    + exam.getId();
            List<UserMessage> messages = new ArrayList<>(studentIds.size());
            for (Long studentId : studentIds) {
                messages.add(buildMessage(studentId, exam.getId(),
                        "成绩发布通知", content));
            }
            insertInBatches(messages);
            log.info("成绩发布通知投递 {} 条：exam={}", studentIds.size(), exam.getId());
        } catch (Exception e) {
            // 旁路降级：通知插入失败绝不阻断成绩发布主事务
            log.warn("成绩发布通知投递失败（旁路降级，不影响发布）：exam={} err={}",
                    exam.getId(), e.toString());
        }
    }

    /**
     * 成绩复核结果通知：向复核申诉学生（review.studentId）投一条。
     * content 包含处理结论（已同意 / 已驳回）与考试 ID；若同意且调分，附带调整后总分。
     */
    public void notifyScoreReviewHandled(ScoreReview review, Exam exam,
                                         String action, BigDecimal adjustedScore) {
        try {
            String resultText = "AGREE".equalsIgnoreCase(action) ? "已同意" : "已驳回";
            String scoreText = "";
            if ("AGREE".equalsIgnoreCase(action) && adjustedScore != null) {
                scoreText = "，调整后总分为 " + adjustedScore.toPlainString() + " 分";
            }
            String content = "您对考试《" + paperTitle(exam) + "》的成绩复核申请已处理，结果为"
                    + resultText + scoreText + "。考试ID：" + exam.getId();
            messageMapper.insert(buildMessage(review.getStudentId(), exam.getId(),
                    "成绩复核结果通知", content));
            log.info("成绩复核结果通知投递：review={} student={} exam={}", review.getId(),
                    review.getStudentId(), exam.getId());
        } catch (Exception e) {
            // 旁路降级：通知插入失败绝不阻断复核处理主事务
            log.warn("成绩复核结果通知投递失败（旁路降级，不影响处理）：review={} err={}",
                    review.getId(), e.toString());
        }
    }

    /** 组装一条未读站内消息。 */
    private UserMessage buildMessage(Long userId, Long examId, String title, String content) {
        UserMessage message = new UserMessage();
        message.setUserId(userId);
        message.setExamId(examId);
        message.setTitle(title);
        message.setContent(content);
        message.setReadFlag(UserMessage.READ_FLAG_UNREAD);
        message.setCreatedTime(LocalDateTime.now());
        return message;
    }

    /**
     * 分批批量写入（每批 ≤{@link #INSERT_BATCH_SIZE} 行）：由外层 try-catch 统一兜底——
     * 任一批失败即抛出，外层记录 warn 后静默返回，绝不阻断考试发布 / 成绩发布主事务。
     */
    private void insertInBatches(List<UserMessage> messages) {
        for (int from = 0; from < messages.size(); from += INSERT_BATCH_SIZE) {
            int to = Math.min(from + INSERT_BATCH_SIZE, messages.size());
            messageMapper.insertBatch(messages.subList(from, to));
        }
    }

    /** 试卷标题（考试快照前以 papers.title 为准；查询失败兜底不炸通知）。 */
    private String paperTitle(Exam exam) {
        if (exam.getPaperId() == null) {
            return "未命名试卷";
        }
        Paper paper = paperMapper.selectById(exam.getPaperId());
        return paper == null || paper.getTitle() == null ? "未知试卷" : paper.getTitle();
    }

    /** 开考时间格式化（YYYY-MM-dd HH:mm）。 */
    private String formatStart(Exam exam) {
        if (exam.getStartTime() == null) {
            return "";
        }
        return exam.getStartTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
}