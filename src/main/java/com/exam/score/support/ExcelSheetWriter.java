package com.exam.score.support;

import com.exam.common.BusinessException;
import com.exam.common.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * SXSSF 流式工作表工具：把"防 OOM 的 Excel 写出管道"从成绩导出业务中剥离出来。
 *
 * <p><b>为什么要拆出来：</b>拆分前 {@code ScoreExportService} 同时承担两类职责——五种报表的
 * 业务编排，与 Excel 写出管道（工作簿生命周期、表头样式、列宽自适应）。后者与"成绩"毫无关系：
 * 任何模块要导 xlsx 都得再抄一遍，而调整表头样式却要改动一个业务服务。拆出后导出服务只剩
 * "查数 + 填格"的业务语义，Excel 管道成为可复用、可独立验证的基础设施。
 *
 * <p><b>为什么是静态工具类而不是 Spring Bean：</b>这些方法无状态，也不依赖任何容器能力
 * （配置、数据源、事务、切面），注册成 Bean 只会增加装配与 mock 成本，换不来任何收益。
 *
 * <p><b>防 OOM 的关键</b>在 {@link #withStreamingWorkbook}：SXSSF 内存中只保留最近
 * {@value #SXSSF_WINDOW} 行，其余行压缩落临时文件，工作簿内存占用与总行数解耦
 * （若用 XSSF，全部单元格堆在堆内，数千行即可能 OOM）。
 */
@Slf4j
public final class ExcelSheetWriter {

    /** SXSSF 滑动窗口：内存中保留的行数，超出自动刷到临时文件 */
    private static final int SXSSF_WINDOW = 100;

    private ExcelSheetWriter() {
    }

    /**
     * SXSSF 模板方法：创建滑动窗口工作簿 → 交给调用方填内容 → 单次序列化 → 清理临时文件。
     *
     * <p>做成"传入 composer 回调"而非"返回工作簿让调用方自己关"，
     * 是为了把 dispose 这个容易漏的清理动作收进 finally——
     * 漏掉它会持续堆积临时文件，且这种泄漏在功能测试中完全看不出来。
     */
    public static byte[] withStreamingWorkbook(Consumer<SXSSFWorkbook> composer) {
        // SXSSF(100)：内存仅保留 100 行窗口，其余行压缩刷盘——防 OOM 的关键开关
        SXSSFWorkbook workbook = new SXSSFWorkbook(SXSSF_WINDOW);
        try {
            composer.accept(workbook);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "Excel 生成失败");
        } finally {
            // 必须显式 dispose：删除 SXSSF 刷盘产生的临时文件（否则堆积磁盘垃圾）
            workbook.dispose();
            try {
                workbook.close();
            } catch (IOException e) {
                log.warn("工作簿关闭异常", e);
            }
        }
    }

    /**
     * 建表并开启全列宽跟踪：SXSSF 的 autoSizeColumn 只能作用于已跟踪列，
     * 且跟踪必须在写行之前开启（滑动窗口外的行已刷盘，事后无法测量）。
     */
    public static SXSSFSheet createSheet(SXSSFWorkbook workbook, String name) {
        SXSSFSheet sheet = workbook.createSheet(name);
        sheet.trackAllColumnsForAutoSizing();
        return sheet;
    }

    /** 在首行写表头。 */
    public static void writeHeader(Sheet sheet, Object... headers) {
        writeHeaderAt(sheet, 0, headers);
    }

    /** 在指定行写表头（个人成绩单需要在标题/信息行下方再起表头，故行号可指定）。 */
    public static void writeHeaderAt(Sheet sheet, int rowIndex, Object... headers) {
        Row row = sheet.createRow(rowIndex);
        CellStyle headerStyle = headerStyle(sheet.getWorkbook());
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(String.valueOf(headers[i]));
            cell.setCellStyle(headerStyle);
        }
    }

    /** 表头样式：加粗 + 灰底，便于在长表中定位列。 */
    public static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /** 加粗样式（用于标题行）。 */
    public static CellStyle boldStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    /** 列宽自适应（只对前 columns 列生效，防止超宽表逐列测量耗时过长）。 */
    public static void autoSize(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}
