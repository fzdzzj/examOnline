package com.exam.exam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 补考名单（spec「补考独立记录」「名单限制进入」，§8.2/§12.5）：
 * 教师组织补考时把勾选的学生写入本表，限制只有名单内学生可进入补考考试。
 *
 * <p><b>为什么补考是独立考试记录 + 独立名单而非复用主考</b>（§12.5）：补考拥有独立的
 * 时间窗/时长/迟到容忍/成绩规则，若复用主考记录则主考的时间窗、答卷、成绩规则都会被补考
 * 污染；补考作为 {exams.parent_exam_id} 指向主考的独立记录，辅以本名单限制进入，二者互不影响。
 *
 * <p>名单独立于答卷（exam_submissions）：只有进入补考后才有答卷，名单是进入前的准入闸。
 * uk_exam_student 唯一索引 + INSERT IGNORE 保证幂等（重复组织同场补考不产生脏名单）。
 */
@Data
@TableName("exam_candidates")
public class ExamCandidate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 补考考试（exams 中 parent_exam_id 指向主考的独立记录） */
    private Long examId;

    /** 被指定补考的学生（user id） */
    private Long studentId;

    private LocalDateTime createdTime;
}