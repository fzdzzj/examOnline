package com.exam.score.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 补考最终成绩查询响应（spec「补考成绩规则·最终成绩经接口可查」，接线收口遗留 #5）：
 * 沿主考费家族（主考 + 各次补考）按考试配置规则（takeHighest/takeLatest/takeAverage）
 * 合并的最终成绩；历史各次成绩在处理服务内只读、永不覆盖。
 *
 * <p>{@code finalScore} 为该学生一场都没应考（无已批改记录）时为 null；
 * {@code reviewing} 为学生查本人且存在进行中成绩复核时隐藏分数（口径同 {@link MyScoreResponse}）。
 */
@Data
public class MakeupFinalScoreResponse {

    /** 被查询的考试 ID（主考或补考均可，服务端沿 parent_exam_id 定位主考合并）。 */
    private Long examId;

    private Long studentId;

    /** 按规则合并后的最终成绩；无记录时为 null。 */
    private BigDecimal finalScore;

    /** 复核中标记（§5.4）：true = 学生本人这一家族存在进行中复核，finalScore 被隐藏置 null。 */
    private Boolean reviewing;
}