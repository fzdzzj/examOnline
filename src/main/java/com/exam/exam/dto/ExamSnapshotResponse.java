package com.exam.exam.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 考试快照响应（spec「考试快照」需求）：
 * exam 为考试配置副本，paper 为完整试卷内容副本（题目/归一化答案/试卷内分值/题号顺序）；
 * 发布时一次性生成，之后重复读取返回完全一致的内容——
 * 后续答题/判分/回看一律以快照为准，试卷或题目再修改均不影响（§10.10）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExamSnapshotResponse {

    private Long id;

    private Long examId;

    private Integer version;

    /** 考试配置快照（解析后的 JSON） */
    private JsonNode exam;

    /** 试卷内容快照（解析后的 JSON，结构与试卷快照一致） */
    private JsonNode paper;

    private LocalDateTime createdTime;
}
