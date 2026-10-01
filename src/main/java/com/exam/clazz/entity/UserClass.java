package com.exam.clazz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学生-班级关联实体（表名 user_class）。
 *
 * <p><b>为什么转班只更新本表 class_id 而成绩随人</b>（决策记录 §12.6）：
 * 答卷表 exam_submissions 以 student_id 绑定学生个人，成绩全部落在学生名下、
 * 不依赖其所属班级；班级只是"组织归属"，因此学生从 A 班转至 B 班只需把本表
 * class_id 从 A 改为 B，历史成绩天然跟随学生、无需任何迁移。
 * 后续"报表按当前班级统计 / 应考名单推导"都以本表当前 class_id 为准。
 *
 * <p>约束：uk_user_class(user_id, class_id) 唯一索引兜底"一人一班一条关联"的幂等，
 * 重复入班/重复转班由索引或 Service 预检拦截。
 */
@Data
@TableName("user_class")
public class UserClass {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学生用户 ID */
    private Long userId;

    /** 所属班级 ID（转班 = 更新本字段） */
    private Long classId;

    /** 入班时间：入班时写入；转班时刷新为转班时间（对目标班而言是新归属起点） */
    private LocalDateTime joinedTime;
}
