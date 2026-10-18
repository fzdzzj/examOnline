package com.exam.exam.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 考中应急顺延与个性化补时请求（add-exam-time-extension）：
 * <ul>
 *   <li>{@code studentIds} 为 null 或空集合 → 全场顺延（Global Extension）；</li>
 *   <li>{@code studentIds} 指定考生 → 个人补时（Individual Compensation），仅更新指定考生在途答卷。</li>
 * </ul>
 * 单次顺延/补时 1~240 分钟（Bean Validation 前置拦截越界值）。
 */
@Data
public class ExamTimeExtensionRequest {

    /** 顺延/补时分钟数：1~240（机房断电、网络抖动等突发场景的单次应急上限）。 */
    @NotNull(message = "extensionMinutes 不能为空")
    @Min(value = 1, message = "顺延时长至少 1 分钟")
    @Max(value = 240, message = "单次顺延时长不能超过 240 分钟")
    private Integer extensionMinutes;

    /** 目标考生 ID 列表：null/空 = 全场顺延；指定 = 仅对这些考生的在途答卷补时。 */
    private List<Long> studentIds;

    /** 顺延/补时原因（如"机房突发断电应急顺延"），落审计摘要。 */
    @Size(max = 255, message = "原因最长 255 字")
    private String reason;
}
