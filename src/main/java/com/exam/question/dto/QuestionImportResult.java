package com.exam.question.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 题库批量导入结果（决策 12.7 落地）：正确行入库、错误行跳过并生成报告。
 * 响应形态 {@code {imported, failed, errors:[{row, reason}]}}，不持久化。
 */
public class QuestionImportResult {

    /** 成功入库的行数。 */
    private int imported;

    /** 失败跳过的行数（data 行，不含表头）。 */
    private int failed;

    /** 逐行错误报告（行号 + 原因），按行序排列。 */
    private List<ErrorRow> errors = new ArrayList<>();

    public int getImported() {
        return imported;
    }

    public void setImported(int imported) {
        this.imported = imported;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(int failed) {
        this.failed = failed;
    }

    public List<ErrorRow> getErrors() {
        return errors;
    }

    public void setErrors(List<ErrorRow> errors) {
        this.errors = errors;
    }

    /** 单行错误：行号（Excel 实际行号，表头为第 1 行）+ 人类可读原因。 */
    public static class ErrorRow {
        /** Excel 中的行号（1 起，表头第 1 行）。 */
        private int row;
        private String reason;

        public ErrorRow() {
        }

        public ErrorRow(int row, String reason) {
            this.row = row;
            this.reason = reason;
        }

        public int getRow() {
            return row;
        }

        public void setRow(int row) {
            this.row = row;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}