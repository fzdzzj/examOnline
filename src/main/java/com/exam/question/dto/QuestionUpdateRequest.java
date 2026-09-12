package com.exam.question.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 更新题目请求：全量覆盖，字段语义与创建一致。
 * 唯一差异：tagIds 为 null 时表示保留原标签关联（传空列表则清空）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QuestionUpdateRequest extends QuestionCreateRequest {
}
