package com.exam.grading.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 手动给分请求（spec「判分失败处理」：判分失败支持重判或手动给分）。
 *
 * @param objectiveScore 教师裁定该答卷的客观题总分（上限为快照客观题满分，服务端校验）
 */
public class ManualScoreRequest {

    @NotNull(message = "客观题总分不能为空")
    @DecimalMin(value = "0", message = "分数不能为负")
    @Digits(integer = 3, fraction = 1, message = "分数最多 1 位小数")
    private BigDecimal objectiveScore;

    public BigDecimal getObjectiveScore() {
        return objectiveScore;
    }

    public void setObjectiveScore(BigDecimal objectiveScore) {
        this.objectiveScore = objectiveScore;
    }
}
