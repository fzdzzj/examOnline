package com.exam.score.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 班级匿名榜单响应（spec「班级匿名榜单」需求）：
 * 前 10 名列表（脱敏）+ 本人不在前 10 时的榜尾行。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScoreLeaderboardResponse implements Serializable {

    private Long examId;

    private String examTitle;

    /** 本人不在前 10 时的榜尾行；若本人已在前 10 内则为 null */
    private LeaderboardMyRow myRow;

    /** 前 10 名榜单（LIMIT 10） */
    private List<LeaderboardItem> top;
}
