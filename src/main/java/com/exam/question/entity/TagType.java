package com.exam.question.entity;

import java.util.Arrays;

/**
 * 标签类型枚举：扁平四类（spec「标签体系」需求）。
 * 标签为教师共享的全局资源，tags.type 列存储枚举码。
 */
public enum TagType {

    /** 学科标签（如：数学） */
    SUBJECT("学科"),
    /** 难度标签（如：简单） */
    DIFFICULTY("难度"),
    /** 题型标签（如：计算题） */
    QUESTION_TYPE("题型"),
    /** 自定义标签（教师自由扩展） */
    CUSTOM("自定义");

    private final String label;

    TagType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 校验标签类型码是否合法（tags.type 为 VARCHAR 列）。 */
    public static boolean isValid(String code) {
        return Arrays.stream(values()).anyMatch(t -> t.name().equals(code));
    }
}
