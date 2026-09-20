# 规范差异：observability（并发监控）

本文件包含对 `spec/specs/observability/spec.md` 的规范变更。

## MODIFIED Requirements

### Requirement: 业务指标监控

**原需求文本**:
```markdown
WHEN 执行关键业务操作，
系统 SHALL 暴露 Micrometer 计数器。
```

**新需求文本**:
```markdown
WHEN 执行关键业务操作，
系统 SHALL:
1. 暴露计数器（Counter）统计成功/失败次数
2. 暴露直方图（Histogram）统计耗时分布
3. 针对分布式锁操作，额外暴露等待时间指标
```

#### Scenario: 事务操作监控
GIVEN 考试发布操作
WHEN 发布完成
THEN Prometheus 可见：
  - exam_publish_success_total (Counter)
  - exam_publish_fail_total (Counter)
  - exam_publish_duration_seconds (Histogram)

#### Scenario: 锁等待监控
GIVEN 分布式锁获取操作
WHEN 锁获取完成
THEN Prometheus 可见：
  - exam_submit_lock_wait_seconds (Histogram)
  - exam_submit_lock_acquisitions_total (Counter)
  - exam_submit_lock_contentions_total (Counter)

---

## ADDED Requirements

### Requirement: 重复率监控

```markdown
### Requirement: 任务重复检测
WHEN 执行定时扫描任务（如 Sweep），
系统 SHALL 记录并上报重复扫描率。

#### Scenario: Sweep 重复率
GIVEN 多实例部署环境
WHEN 执行 sweep 任务
THEN 上报指标 exam_sweep_duplicate_total
AND 计算重复率 = duplicate_count / total_scanned
AND 重复率 > 10% 时触发告警

#### Scenario: 去重有效性
GIVEN Redis 锁 + 数据库唯一索引
WHEN 并发执行 sweep
THEN 重复处理率 < 5%
AND 监控面板显示去重效率趋势
```

---

### Requirement: 热点 Key 识别

```markdown
### Requirement: 锁热点分析
WHEN 锁等待时间 P95 > 100ms，
系统 SHALL 标记该 Key 为热点 Key。

#### Scenario: 热点 Key 告警
GIVEN exam:submit:lock:{id} 等待时间异常
WHEN 检测到 P95 > 100ms 持续 5 分钟
THEN 记录审计日志"hot_lock_key_detected"
AND 上报指标 exam_lock_hotspot_detected_total
AND 通知运维团队分析原因
```

---

## REMOVED Requirements

无移除项。

---

## 规范变更总结

| 类型 | 需求名称 | 变更说明 |
|------|---------|----------|
| MODIFIED | 业务指标监控 | 新增锁等待时间要求 |
| ADDED | 重复率监控 | Sweep 任务重复率统计 |
| ADDED | 热点 Key 识别 | 锁竞争热点告警 |

---

## 验证清单

- [ ] `curl http://localhost:8080/actuator/prometheus | grep exam_submit_lock` 可见指标
- [ ] Grafana Dashboard 展示锁等待 P95 < 100ms
- [ ] `exam_sweep_duplicate_total` 计数器正常上报
- [ ] 压测报告：100 并发下锁等待无显著增加
- [ ] 全量测试通过，无回归问题

---

## 参考示例

### ✅ 正确示例：锁监控实现

```java
@Service
public class ExamSubmitService {
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    public ApiResponse<?> submit(Long examId, SubmitRequest request) {
        String lockKey = "exam:submit:lock:" + examId;
        long startTime = System.nanoTime();
        
        try {
            Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, uuid, 30, TimeUnit.SECONDS);
            
            // 上报等待时间
            double waitTimeSeconds = (System.nanoTime() - startTime) / 1e9;
            meterRegistry.timer("exam_submit_lock_wait_seconds")
                .record(waitTimeSeconds);
            
            meterRegistry.counter("exam_submit_lock_acquisitions_total").increment();
            
            // ... 提交逻辑
            
        } catch (Exception e) {
            meterRegistry.counter("exam_submit_lock_contentions_total").increment();
            throw e;
        }
    }
}
```
