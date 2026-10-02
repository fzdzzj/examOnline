package com.exam.question.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量删除结果（逐题结果信封）：部分成功如实区分，不把部分成功伪装成全部成功。
 * 全部失败时 succeeded 为空列表、HTTP 仍 200——请求校验失败（空/超限/重复）才是 400。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchDeleteQuestionsResponse {

    /** 软删除成功的题目 ID */
    private List<Long> succeeded;

    /** 逐题失败项：id + 与单题删除同类语义的失败原因 */
    private List<FailedItem> failed;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedItem {

        private Long id;

        private String reason;
    }
}
