package com.exam.support;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 跨实例取号护栏：不同测试实例必须共享同一取号状态，且取到的名字不得重复。
 * 旧实现（8bd04c2 之前）按实例构造时刻取毫秒种子再对 100_000 取模——同毫秒构造的两个实例首个取号必然同名；
 * 旧实现是否暴露取决于两次构造是否恰好同毫秒，任何靠紧凑循环凑次数、睡眠或比拼执行速度的断言都不构成确定性反例。
 * 本护栏改为反射比较两个实例各自持有的取号器对象身份：实例级播种下两个实例必然持有不同 AtomicLong，护栏无条件失败；
 * JVM 级共享计数下两个实例必然持有同一 AtomicLong，护栏通过，且两次取名因单调递增必然不同。
 * 不重置共享计数、不依赖时钟与构造落点。
 */
class UniqueSeqGuardTest {

    /** 只为触达 protected 取号方法与取号器字段的最小子类：裸 new，不走 Spring 生命周期。 */
    private static final class Probe extends IntegrationTestBase {
        String draw() {
            return unique("guard-probe");
        }
    }

    @Test
    void instancesShareOneSeqGeneratorAndDrawDistinctNames() throws Exception {
        Field seqField = IntegrationTestBase.class.getDeclaredField("seq");
        seqField.setAccessible(true);

        Probe first = new Probe();
        Probe second = new Probe();

        assertSame(seqField.get(first), seqField.get(second),
                "两个测试实例持有不同的取号器实例——实例各自播种会让同毫秒构造的实例取出同名账号");

        String firstName = first.draw();
        String secondName = second.draw();
        assertNotEquals(firstName, secondName,
                () -> "跨实例取号出现重复: " + firstName);
    }
}
