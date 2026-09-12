package com.exam.paper.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** 调整试卷题目顺序请求：按传入的题目 ID 顺序重排题号（1..n）。 */
@Data
public class PaperOrderRequest {

    /** 期望顺序的题目 ID 全量列表（必须与试卷现有题目一一对应） */
    @NotEmpty(message = "题目顺序列表不能为空")
    private List<Long> questionIds;
}
