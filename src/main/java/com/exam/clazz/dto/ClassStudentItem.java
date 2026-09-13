package com.exam.clazz.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 班级学生列表项：学生基本信息 + 入班时间。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassStudentItem {

    private Long userId;

    /** 学号 */
    private String username;

    private String name;

    /** 入班时间（转班学生为转入该班的时间） */
    private LocalDateTime joinedTime;
}
