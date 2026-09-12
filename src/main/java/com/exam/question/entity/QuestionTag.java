package com.exam.question.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 题目-标签关联：一题可挂多个标签（多选），一标签可被多题引用。
 * 纯关联行，物理删除（随题目标签更新或标签删除清理），无软删需求。
 */
@Data
@TableName("question_tags")
public class QuestionTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long questionId;

    private Long tagId;
}
