package com.exam.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 邀请码视图（管理员列表/创建返回）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InviteCodeResponse {

    private Long id;
    private String code;
    private String note;
    /** 0=有效 1=已作废 */
    private Integer status;
    private Integer usedCount;
    private Long createdBy;
    private LocalDateTime createdTime;
}
