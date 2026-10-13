package com.exam.question.support;

/**
 * 题库导入/导出模板的列定义（单一事实源，防漂移）。
 *
 * <p>模板产出（{@code QuestionImportExportService} 的表头与示例行）与导入解析
 * （逐列 {@code cellIndex()} 取值）共用本枚举——导出列口径与导入模板逐字一致，
 * round-trip 可用。固定列序 12 列：题型/题干/选项A–F/答案/标签/难度/图片URL。
 */
public enum QuestionImportColumn {

    TYPE(0, "题型"),
    CONTENT(1, "题干"),
    CHOICE_A(2, "选项A"),
    CHOICE_B(3, "选项B"),
    CHOICE_C(4, "选项C"),
    CHOICE_D(5, "选项D"),
    CHOICE_E(6, "选项E"),
    CHOICE_F(7, "选项F"),
    ANSWER(8, "答案"),
    TAGS(9, "标签"),
    DIFFICULTY(10, "难度"),
    IMAGE_URL(11, "图片URL");

    private final int index;
    private final String title;

    QuestionImportColumn(int index, String title) {
        this.index = index;
        this.title = title;
    }

    /** 列序号（0 起）。 */
    public int cellIndex() {
        return index;
    }

    /** 表头文案（导出/模板逐字一致）。 */
    public String title() {
        return title;
    }

    /** 全部表头（按列序）。 */
    public static String[] headers() {
        QuestionImportColumn[] cols = values();
        String[] headers = new String[cols.length];
        for (int i = 0; i < cols.length; i++) {
            headers[i] = cols[i].title;
        }
        return headers;
    }

    /** 列总数。 */
    public static int columnCount() {
        return values().length;
    }
}