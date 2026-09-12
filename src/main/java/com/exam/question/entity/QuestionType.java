package com.exam.question.entity;

/**
 * 题型枚举：与 questions.type 列及前端约定一致。
 * 仅 4 类（单选/多选/判断/简答），代码/公式/听力/口语等题型已在方案中裁剪。
 */
public enum QuestionType {

    /** 单选题：答案为单个选项字母 */
    SINGLE(1, "单选"),
    /** 多选题：答案为升序选项字母列表 */
    MULTIPLE(2, "多选"),
    /** 判断题：答案归一化为 T/F */
    JUDGE(3, "判断"),
    /** 简答题：答案为参考答案原文 */
    SHORT_ANSWER(4, "简答");

    private final int code;
    private final String label;

    QuestionType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 按列值解析题型，非法值返回 null（调用方负责报参数错误）。 */
    public static QuestionType of(Integer code) {
        if (code == null) {
            return null;
        }
        for (QuestionType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }
}
