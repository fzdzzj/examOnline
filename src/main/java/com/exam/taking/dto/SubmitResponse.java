package com.exam.taking.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 交卷响应：重复交卷幂等返回首次结果（status/submitTime/submitType 与首次一致）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmitResponse {

    private Long submissionId;

    private Long examId;

    private Long studentId;

    /** 答卷状态：2=已交卷 */
    private Integer status;

    /** 交卷时间（首次提交的服务端时间） */
    private LocalDateTime submitTime;

    /** 首次提交来源：1手动 2前端归零 3后端兜底 */
    private Integer submitType;
}
