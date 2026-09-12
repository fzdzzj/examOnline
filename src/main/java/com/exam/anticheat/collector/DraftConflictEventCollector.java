package com.exam.anticheat.collector;

import com.exam.anticheat.model.SeverityLevel;
import org.springframework.stereotype.Component;

/**
 * 草稿冲突事件策略：多端自动保存版本冲突（阶段 5 已有采集点，本阶段纳入策略体系）。
 *
 * <p>多端冲突已被"以最新版本为准"的合并规则根治（spec「多端冲突以最新为准」场景），
 * 记录它主要用于发现"同一账号多设备同时作答"的防作弊线索（决策记录 §3.2 踢旧会话
 * 的辅助证据）。基础严重度按"低"：单次冲突大多是断线重连的自然结果。
 */
@Component
public class DraftConflictEventCollector implements BehaviorEventCollector {

    @Override
    public String eventType() {
        return BehaviorEventTypes.DRAFT_CONFLICT;
    }

    @Override
    public SeverityLevel baseSeverity() {
        return SeverityLevel.LOW;
    }
}
