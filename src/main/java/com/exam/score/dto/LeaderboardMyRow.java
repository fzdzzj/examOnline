package com.exam.score.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 本人榜尾行：当学生本人名次不在前 10 时单独返回，不混入前 10 截断。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaderboardMyRow implements Serializable {

    /** 本人竞赛排名 */
    private int rank;

    /** 本人总分 */
    private BigDecimal totalScore;

    /** 是否本人（恒为 true） */
    private Boolean isMe;
}
