package com.exam.adaptive.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * CAT 自适应测验开局请求（spec add-irt-adaptive-testing）：
 * 题数与 SE 阈值均可配，缺省沿用规范（10 题 / SE &lt; 0.30）。
 */
@Data
public class AdaptiveQuizStartRequest {

    /** 最大题数（1~20，缺省 10） */
    @Min(value = 1, message = "最大题数不能小于 1")
    @Max(value = 20, message = "最大题数不能大于 20")
    private Integer maxItems;

    /** 终止阈值 SE（缺省 0.30；服务端再钳制到 [0.05, 2.0] 防呆） */
    private Double seThreshold;
}
