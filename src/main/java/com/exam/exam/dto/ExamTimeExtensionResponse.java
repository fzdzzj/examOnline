package com.exam.exam.dto;

import java.time.LocalDateTime;

/**
 * 考中应急顺延与个性化补时结果（add-exam-time-extension）：
 *
 * @param examId                    考试 ID
 * @param extensionMinutes          本次顺延/补时分钟数
 * @param isGlobal                  true=全场顺延（studentIds 为空），false=个人补时
 * @param affectedSubmissionsCount  实际受影响的在途答卷数（个人模式中已交卷的考生不计入）
 * @param newEndTime                顺延后考试结束时间（个人模式保持原值）
 * @param newDurationMinutes        顺延后个人作答时长（个人模式保持原值）
 */
public record ExamTimeExtensionResponse(
        Long examId,
        Integer extensionMinutes,
        boolean isGlobal,
        int affectedSubmissionsCount,
        LocalDateTime newEndTime,
        Integer newDurationMinutes) {
}
