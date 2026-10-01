package com.exam.config;

import org.springframework.beans.factory.annotation.Value;

/**
 * 读己之写标记（线程级 ThreadLocal，add-performance-deepening task4）：
 * 写操作（交卷/发布/批改）完成后调用 {@link #mark()}，在当前线程留下一枚时间戳；
 * 短窗口内该线程的 {@code @DS("slave")} 读会被 {@link ReadYourWriteRouter} 强制转主库。
 * 窗口过后 {@link #isWindowActive()} 返回 false 并清空标记，读恢复走从库。
 *
 * <p><b>为什么写后立即读可能读到旧数据：</b>主从不做同步提交，数据靠异步 GTID 复制，
 * 主库事务提交后从库往往滞后几十毫秒~秒级。若此刻紧接一条从库读，会拿到复制尚未到达的
 * 旧快照——刚交的卷/刚发的成绩查不到或显示旧状态。
 *
 * <p><b>为什么用短窗口而非永久路由主库：</b>永久走主库会让从库形同虚设、彻底失去
 * 读写分离放大读吞吐的收益。短窗口只覆盖复制延迟的峰值窗口（默认 5s），过期即复位，
 * 让绝大多数读继续走从库，是"一致性"与"读吞吐"的平衡点。
 *
 * <p>注：标记仅线程级，只能约束"同一线程内写后紧接着读"；跨请求的强一致读
 * （答卷详情/成绩，见 ExamSubmissionService/ScoreService）本就默认走 master，不依赖本标记。
 */
public class ReadYourWriteMark {

    private final ThreadLocal<Long> writeAtMillis = new ThreadLocal<>();

    private final long windowMillis;

    public ReadYourWriteMark(
            @Value("${exam.datasource.read-your-write-window-ms:5000}") long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /** 标记当前线程刚完成一次写。 */
    public void mark() {
        writeAtMillis.set(System.currentTimeMillis());
    }

    /** 是否处于写后读己之写窗口：窗口内返回 true；窗口过期清除标记并返回 false。 */
    public boolean isWindowActive() {
        Long at = writeAtMillis.get();
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at < windowMillis) {
            return true;
        }
        writeAtMillis.remove();
        return false;
    }

    /** 手动清除标记（测试或异常兜底用）。 */
    public void clear() {
        writeAtMillis.remove();
    }
}