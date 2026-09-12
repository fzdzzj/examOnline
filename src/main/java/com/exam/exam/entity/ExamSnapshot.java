package com.exam.exam.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考试快照（spec「考试快照」需求）：考试发布时一次性序列化的"考试配置 + 完整试卷内容"副本。
 * 只写不改（exam_id 唯一，发布是快照生成的唯一时机）：
 * 之后试卷或题目被修改均不影响快照，答题/判分/回看一律读快照（§10.10）。
 */
@Data
@TableName("exam_snapshots")
public class ExamSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long examId;

    /**
     * 考试配置 JSON：
     * {examId, title, paperId, courseId, classId, startTime, endTime,
     *  durationMinutes, allowLateMinutes, antiCheatConfig, generatedTime}
     */
    private String examJson;

    /**
     * 试卷内容 JSON（与 paper_snapshots.paper_json 结构一致，复用同一序列化）：
     * {paperId, title, totalScore, questionCount, questions: [{number, questionId, type,
     *  content, choices, correctAnswer, score}]}
     */
    private String paperJson;

    /** 快照版本：一场考试只生成一次，当前固定 1 */
    private Integer version;

    /** 生成人（考试创建教师） */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
