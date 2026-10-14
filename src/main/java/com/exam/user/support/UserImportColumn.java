package com.exam.user.support;

/**
 * 用户/学生批量导入导出列定义（单一事实源，防漂移）。
 *
 * <p>模板产出、导入解析、导出（scope=student）共用本枚举。
 * 固定列序 5 列：学号/姓名/邮箱/初始密码/班级名称。
 */
public enum UserImportColumn {

    USERNAME(0, "学号"),
    NAME(1, "姓名"),
    EMAIL(2, "邮箱"),
    PASSWORD(3, "初始密码"),
    CLASS_NAME(4, "班级名称");

    private final int index;
    private final String title;

    UserImportColumn(int index, String title) {
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
        UserImportColumn[] cols = values();
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
