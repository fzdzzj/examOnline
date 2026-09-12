package com.exam.grading.dto;

import java.util.List;

/**
 * 整场判分执行结果：只展开失败明细（成功份数用统计值表达，避免大响应）。
 */
public record GradingRunResponse(int total, int success, int failed, List<FailureItem> failures) {

    /** 失败答卷条目：定位到答卷 + 现场原因（教师排查/手动给分依据）。 */
    public record FailureItem(Long submissionId, Long studentId, String error) {
    }
}
