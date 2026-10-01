package com.exam.clazz.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 更新班级请求（部分更新：字段为空则不修改对应列）。 */
@Data
public class ClassUpdateRequest {

    @Size(max = 64, message = "班级名最长 64 字")
    private String name;

    /** 课程 ID（传 null 表示不修改） */
    private Long courseId;
}
