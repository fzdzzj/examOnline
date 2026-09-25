package com.exam.support;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨实例取号护栏：不同测试实例取到的名字不得重复。
 * 旧实现按实例构造时刻取毫秒种子再对 100_000 取模——同毫秒构造的两个实例首个取号必然同名，
 * 本用例在紧凑循环里裸构造大量实例迫使同毫秒出现（鸽笼原理），对旧实现稳定变红；
 * 取号器改为 JVM 级单调计数后恒绿。
 */
class UniqueSeqGuardTest {

    /** 只为触达 protected 取号方法的最小子类：裸 new，不走 Spring 生命周期。 */
    private static final class Probe extends IntegrationTestBase {
        String draw() {
            return unique("guard-probe");
        }
    }

    @Test
    void namesDrawnByDistinctInstancesNeverCollide() {
        int probes = 1000;
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (int i = 0; i < probes; i++) {
            String name = new Probe().draw();
            if (!seen.add(name)) {
                duplicates.add(name);
            }
        }
        assertTrue(duplicates.isEmpty(),
                () -> "跨实例取号出现重复: " + duplicates
                        + "（唯一名 " + seen.size() + "/" + probes + "）");
    }
}
