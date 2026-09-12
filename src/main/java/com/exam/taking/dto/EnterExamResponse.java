package com.exam.taking.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 进入考试/答题数据响应（spec「进入考试」「自动保存与断线恢复」）：
 * 同一答卷重复进入返回完全一致的题目与顺序（刷新不换题），并携带
 * 服务端倒计时（前端仅展示）与 Redis 草稿（断线恢复）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnterExamResponse {

    private Long examId;

    private String examTitle;

    private Long submissionId;

    /** 答卷状态：1进行中 2已交卷（已交卷时 questions 为空、remainingSeconds=0） */
    private Integer status;

    /** 个人开始时间（点击开始的服务端时间） */
    private LocalDateTime startTime;

    /** 个人截止时间 */
    private LocalDateTime deadlineTime;

    /** 服务端当前时间：倒计时展示的权威时钟（服务端时间为准，§1.1） */
    private LocalDateTime serverTime;

    /** 服务端计算的剩余秒数（>=0，前端仅展示） */
    private Long remainingSeconds;

    /** 题目列表（个人快照，不含答案；已交卷为空列表） */
    private List<QuestionView> questions;

    /** 草稿答案 JSON（questionId→答案）；无草稿为 null */
    private JsonNode answers;

    /** 标记题 ID 列表（导航面板"标记"色，spec「导航状态标识」） */
    private List<Long> marked;

    /** 草稿版本号：客户端下次自动保存原样携带（多端冲突合并依据）；无草稿为 null */
    private Integer draftVersion;
}
