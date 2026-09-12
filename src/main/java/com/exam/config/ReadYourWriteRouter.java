package com.exam.config;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 读己之写路由切面（add-performance-deepening task4）：
 * 拦截所有 {@code @DS} 注解方法，当方法路由目标是从库({@code slave})、且当前线程正处
 * 写后短窗口时，强制把本次读转到主库({@code master})。
 *
 * <p><b>@DS 与动态路由原理：</b>dynamic-datasource 用 {@code DynamicDataSourceContextHolder}
 * 在线程内以栈保存数据源 key，取连接时 {@code peek()} 栈顶决定命中哪个连接池；{@code @DS}
 * 注解方法执行前 push(key)、执行后 poll() 还原。@DS 切面默认 {@code HighestPrecedence}（最外层）
 * 先将 "slave" 压栈；本切面用 {@code LowestPrecedence}（最内层）在其之后再压入 "master"——
 * 栈顶被覆盖为 master，于是窗口内 slave 读实际命中主库。窗口过后本切面不动作，
 * {@code @DS("slave")} 维持原样走从库。这正是动态数据库"栈 + 注解 + 可叠加切面"的灵活之处。
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
public class ReadYourWriteRouter {

    public static final String MASTER = "master";
    public static final String SLAVE = "slave";

    private final ReadYourWriteMark mark;

    public ReadYourWriteRouter(ReadYourWriteMark mark) {
        this.mark = mark;
    }

    @Around("@annotation(ds)")
    public Object route(ProceedingJoinPoint pjp, DS ds) throws Throwable {
        // 仅对"读从库"且在写后窗口内的调用强制回主库；其余路径保持原路由
        boolean forceMaster = SLAVE.equals(ds.value()) && mark.isWindowActive();
        if (forceMaster) {
            DynamicDataSourceContextHolder.push(MASTER);
        }
        try {
            return pjp.proceed();
        } finally {
            if (forceMaster) {
                DynamicDataSourceContextHolder.poll();
            }
        }
    }
}