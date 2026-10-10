package com.exam.score.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 班级匿名榜单前 10 条目：名次、脱敏姓名（姓+**）、总分、是否本人。
 * 绝不下发他人 studentId，防止枚举。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeaderboardItem implements Serializable {

    /** 竞赛排名：并列同名次、后续跳号（1,2,2,4） */
    private int rank;

    /** 脱敏姓名：非本人为「姓+**」（如「张**」）；本人行实名 */
    private String displayName;

    /** 总分 */
    private BigDecimal totalScore;

    /** 是否为当前查询学生本人 */
    private Boolean isMe;
}
