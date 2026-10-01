package com.exam.exam.service;

import com.exam.exam.entity.Exam;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 补考成绩规则合并单测（spec「补考成绩规则」三态，§5.1）：
 * 纯计算校验 takeHighest / takeLatest / takeAverage，以及无成绩返回 null。
 * 不依赖 Spring/DB，直接以两个 null 依赖实例化服务调用纯合并方法。
 */
class MakeupScoreServiceTest {

    private final MakeupScoreService service = new MakeupScoreService(null, null);

    private MakeupScoreService.ScoreRecord rec(double score, LocalDateTime time) {
        return new MakeupScoreService.ScoreRecord(BigDecimal.valueOf(score), time);
    }

    @Test
    void takeHighestReturnsMax() {
        List<MakeupScoreService.ScoreRecord> records = List.of(
                rec(5.0, t(1)),
                rec(8.0, t(2)),
                rec(7.0, t(3)));
        assertEquals(0, BigDecimal.valueOf(8.0).compareTo(service.mergeFinalScore(
                Exam.MAKEUP_TAKE_HIGHEST, records)));
    }

    @Test
    void takeLatestReturnsMostRecentByTime() {
        // 时间轴最晚的 t(3) 分值为 7.0（勿与最高分混淆，验证取的是"最近"而非"最高"）
        List<MakeupScoreService.ScoreRecord> records = List.of(
                rec(8.0, t(1)),
                rec(5.0, t(2)),
                rec(7.0, t(3)));
        assertEquals(0, BigDecimal.valueOf(7.0).compareTo(service.mergeFinalScore(
                Exam.MAKEUP_TAKE_LATEST, records)));
    }

    @Test
    void takeAverageComputesMean() {
        List<MakeupScoreService.ScoreRecord> records = List.of(
                rec(5.0, t(1)),
                rec(8.0, t(2)),
                rec(7.0, t(3)));
        // (5+8+7)/3 = 6.6666… → 四舍五入保留 1 位 = 6.7
        assertEquals(0, BigDecimal.valueOf(6.7).compareTo(service.mergeFinalScore(
                Exam.MAKEUP_TAKE_AVERAGE, records)));
    }

    @Test
    void emptyRecordsReturnNull() {
        assertNull(service.mergeFinalScore(Exam.MAKEUP_TAKE_HIGHEST, List.of()));
        assertNull(service.mergeFinalScore(Exam.MAKEUP_TAKE_AVERAGE, null));
    }

    private LocalDateTime t(int day) {
        return LocalDateTime.of(2026, 9, day, 10, 0);
    }
}