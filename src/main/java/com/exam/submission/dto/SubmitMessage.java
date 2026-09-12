package com.exam.submission.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 交卷消息（HTTP 交卷 → MQ 削峰 → 消费者批量落库 的载荷）：
 * 核心字段在交卷主事务只做状态 CAS（避免大 JSON 拖慢事务），答案 JSON 随消息异步落库。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmitMessage {

    /** 答卷 ID：消费者按 id 做 casFillAnswers 幂等落库 */
    private Long submissionId;

    private Long examId;

    private Long studentId;

    /** 提交来源：见 ExamSubmission.SUBMIT_TYPE_* 常量 */
    private Integer submitType;

    /** 交卷时间（服务端时间，CAS 迁移时已确定） */
    private LocalDateTime submitTime;

    /** 答案 JSON（questionId→答案）；空答卷为 "{}" */
    private String answers;
}
