package com.exam.anticheat.collector;

/**
 * 行为事件类型常量：前端上报与后端策略注册共用同一套字符串口径。
 *
 * <p>事件类型即策略注册表的分派键（同判分策略按题型分派的设计）——
 * 新增事件类型 = 新增一个常量 + 新增一个策略实现类（@Component 即注册），
 * 采集核心零改动（spec「新增事件不动核心」场景）。
 */
public final class BehaviorEventTypes {

    /** 切屏：学生切出考试页面（visibilitychange 隐藏 / 切换标签页），阶段 5 已埋前端采集点 */
    public static final String SWITCH_SCREEN = "SWITCH_SCREEN";

    /** 失焦：考试页面失去输入焦点（window blur，含唤起系统弹窗等误触），阶段 5 已埋前端采集点 */
    public static final String WINDOW_BLUR = "WINDOW_BLUR";

    /** 刷新：答题页刷新/重载（个人快照保证"刷新不换题"，行为本身仍值得记录——§3.4 防"刷新刷题"） */
    public static final String PAGE_REFRESH = "PAGE_REFRESH";

    /** 交卷异常：交卷链路异常（如交卷消息发送失败答案转草稿兜底），服务端自行采集 */
    public static final String SUBMIT_ANOMALY = "SUBMIT_ANOMALY";

    /** 草稿冲突：多端自动保存版本冲突（以最新版本为准，旧端写入被拒），阶段 5 已有采集点 */
    public static final String DRAFT_CONFLICT = "DRAFT_CONFLICT";

    private BehaviorEventTypes() {
    }
}
