package com.exam.config;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.exam.common.RequestIdFilter;
import com.exam.monitoring.metrics.BusinessMetrics;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 从库读降级回主库执行器（harden-slave-read-failover，B.2 中危 M3）：
 * 三个 {@code @DS("slave")} 非强一致读点（ExamSnapshotService.getCurrent 拉卷主链路 /
 * PaperSnapshotService.getCurrent / GradingQueryService.progress）从库故障时直接 500——
 * 本执行器把读逻辑包为 {@link Supplier}，{@link DataAccessException}（连接失败/超时等基础设施异常）
 * 时 ERROR 日志 + 降级计数（exam.datasource.degraded，tag=endpoint）后<b>压栈 master 重试一次</b>
 * （与 {@link ReadYourWriteRouter} 同一机制：{@code DynamicDataSourceContextHolder.push("master")}
 * 覆盖栈顶路由），主库成功即降级成功；主库也失败则异常原样上抛（诚实失败，不吞）。
 *
 * <p><b>为什么不用 dynamic-datasource 内建切换（方案决策，proposal §二）</b>：4.3.1 的
 * strategy 包只有选库期策略（负载均衡/随机），数据源在<b>执行前</b>选定，无法感知<b>执行期</b>
 * 连接故障并自动重试——内建 failover 不存在，故走读点降级回主库。
 *
 * <p><b>降级口只认不可用，不认业务结果</b>：仅捕获 {@link DataAccessException}；
 * 业务异常（NOT_FOUND / 越权 DENIED 等 {@link com.exam.common.BusinessException}）原样传播，
 * 既不重试也不计数。
 *
 * <p><b>事务边界约束</b>：push/poll 只影响当前线程的路由栈顶；本仓三个读点均无事务
 * （只读 + 缓存互斥回源路径），禁止把带 {@code @Transactional} 的写路径包进本执行器
 * （事务内切换数据源会破坏连接绑定）。
 *
 * <p><b>幂等性前提</b>：重试会把 Supplier 整体重跑一遍（可能含多次读），读点必须幂等——
 * 三个读点均为纯读（含 paperService.getOwnedPaper / requireOwnedExam 等校验读），重跑无副作用。
 */
@Slf4j
@Component
public class SlaveReadFallbackExecutor {

    private final BusinessMetrics businessMetrics;

    public SlaveReadFallbackExecutor(BusinessMetrics businessMetrics) {
        this.businessMetrics = businessMetrics;
    }

    /**
     * 从库读 + 故障降级回主库重试一次。
     *
     * @param endpoint 降级计数 tag（代码常量有界：exam_snapshot / paper_snapshot / grading_progress）
     * @param slaveRead 读逻辑（在 @DS("slave") 路由上下文内执行；主库重试时同一线程压栈 master）
     */
    public <T> T execute(String endpoint, Supplier<T> slaveRead) {
        try {
            return slaveRead.get();
        } catch (DataAccessException e) {
            log.error("从库读降级：数据访问异常，降级回主库重试一次, endpoint={}, 异常={}, requestId={}",
                    endpoint, e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY), e);
            businessMetrics.countDatasourceDegraded(endpoint);
            // 与 ReadYourWriteRouter 同机制：压栈 master 覆盖 @DS("slave") 的栈顶路由，
            // 主库读成功即恢复可用（从库恢复后自然回到原路由，无需任何人工干预）
            DynamicDataSourceContextHolder.push(ReadYourWriteRouter.MASTER);
            try {
                return slaveRead.get();
            } finally {
                DynamicDataSourceContextHolder.poll();
            }
        }
    }
}
