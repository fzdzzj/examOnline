# 阶段 21 前端考务系统 - 后端契约核实报告

## 一、基线数字（改动前）

### 前端门禁
- **lint:check**: ✅ 通过（无 error/warning）
- **type-check:check**: ✅ 通过
- **vitest**: ✅ 83 tests passed (8 files)

### 后端全量测试
- **Tests run: 218, Failures: 0, Errors: 0, Skipped: 1** → BUILD SUCCESS
- Skipped: 1 是契约导出方法受 `exportContract` 开关控制，属设计使然

---

## 二、后端契约核实结论

### 1. 考试状态枚举与迁移规则

**状态取值** (`Exam.java`):
| 编码 | 值 | 说明 |
|-----|---|------|
| STATUS_NOT_STARTED | 0 | 未开始：可编辑（未发布时）、可删除；已发布的到达 start_time 自动迁入进行中 |
| STATUS_IN_PROGRESS | 1 | 进行中：绑定试卷被锁定（只读）；学生答题窗口开放 |
| STATUS_ENDED | 2 | 已结束：到达 end_time 自然结束，或教师提前结束（force_end=1） |
| STATUS_GRADED | 3 | 已批改：判分完成（阶段 5+ 消费） |
| STATUS_PUBLISHED | 4 | 成绩已发布：学生可查成绩（阶段 5+ 消费） |

**状态迁移规则** (`ExamStateMachineService.java` L49-53):
```java
LEGAL_TRANSITIONS = Map.of(
    Exam.STATUS_NOT_STARTED, Set.of(Exam.STATUS_IN_PROGRESS),
    Exam.STATUS_IN_PROGRESS, Set.of(Exam.STATUS_ENDED),
    Exam.STATUS_ENDED, Set.of(Exam.STATUS_GRADED),
    Exam.STATUS_GRADED, Set.of(Exam.STATUS_PUBLISHED)
);
```

**关键发现**:
- 状态机权威在后端，前端严禁自行推算
- 定时任务 `autoAdvance()` 每 10s 扫表推进（L149）
- 启动时补偿扫表（`advanceOnStartup()` L157）
- 并发迁移使用乐观锁 CAS（`casUpdateStatus`）

---

### 2. `anti_cheat_config` 真实字段

**字段定义** (`Exam.java` L111-112):
```java
/** 防作弊配置 JSON 字符串（切屏检测/禁复制等开关；null=使用默认配置） */
private String antiCheatConfig;
```

**请求 DTO** (`ExamCreateRequest.java` L50-51):
```java
/** 防作弊配置 JSON（可选，如 {"switchScreen":true,"forbidCopy":false}） */
private JsonNode antiCheatConfig;
```

**核实结论**:
- 后端仅存储为 JSON 字符串，**无具体字段校验**
- 前端应支持渲染任意 JSON 字段，但**不得新增后端不存在的字段**
- 建议默认配置项：
  - `switchScreen`: boolean (切屏检测)
  - `forbidCopy`: boolean (禁复制)
  - 其他字段由后端实际采集逻辑决定

---

### 3. force-end 的真实端点与副作用

**端点路径** (`ExamController.java` L96-100):
```java
/** 教师提前结束：进行中 → 已结束并留强制交卷标记；状态迁移经乐观锁 CAS，并发仅一次成功 */
@PostMapping("/{id}/force-end")
public ApiResponse<ExamDetailResponse> forceEnd(@PathVariable Long id) {
    return ApiResponse.success(examService.forceEnd(id));
}
```

**缺考标记触发** (`ExamStateMachineService.java` L124-128):
```java
int rows = casAdvanceQuietly(exam, Exam.STATUS_ENDED);
if (rows > 0) {
    // 缺考标记锚定"进行中→已结束"这一刻
    absenceService.markAbsence(exam.getId());
}
```

**核实结论**:
- ✅ **force-end 会触发缺考标记**（在 `STATUS_IN_PROGRESS → STATUS_ENDED` 迁移时调用）
- ⚠️ **确认弹窗必须写明「该动作会触发缺考标记」**
- 缺考标记时机：时间窗彻底关闭、迟到窗口结束时（`markAbsence` 幂等）

---

### 4. 发布考试是否生成试卷快照

**端点路径** (`ExamController.java` L102-106):
```java
/** 发布考试：学生可见、生成考试快照（唯一时机），状态仍为未开始等待定时开考 */
@PostMapping("/{id}/publish")
public ApiResponse<ExamDetailResponse> publish(@PathVariable Long id) {
    return ApiResponse.success(examService.publish(id));
}
```

**实体注释** (`Exam.java` L105-106):
```java
/** 0=未发布（学生不可见）1=已发布（发布即生成考试快照） */
private Integer published;
```

**核实结论**:
- ✅ **发布考试时会生成试卷快照**（`published=1` 时）
- 快照是**唯一时机**生成，之后试卷修改不影响快照内容
- 发布后考试状态仍为 `STATUS_NOT_STARTED`，等待定时开考

---

### 5. 班级与学生归属

**端点列表** (`ClassController.java`):
| 方法 | 路径 | 说明 |
|-----|------|------|
| POST `/api/classes` | 创建班级（归属当前教师） |
| GET `/api/classes` | 班级分页（教师仅见自己归属的班级） |
| GET `/api/classes/{id}` | 班级详情 |
| PUT `/api/classes/{id}` | 更新班级（部分更新） |
| DELETE `/api/classes/{id}` | 删除班级（软删） |
| POST `/api/classes/{id}/students` | 学生入班 |
| DELETE `/api/classes/{id}/students/{userId}` | 学生移出班级 |
| PUT `/api/classes/{id}/students/{userId}/transfer` | 学生转班 |
| GET `/api/classes/{id}/students` | 班级学生列表（该班当前全部学生） |

**硬约束** (项目规范):
- ⚠️ **本阶段不改 `com.exam.clazz` 包**
- 班级相关只调 `ClassService.listStudentIds(Long classId)`（需核实是否存在此方法）

**核实发现**:
- `ClassController` 提供完整 CRUD + 学生管理接口
- `listStudents(id)` 返回 `List<ClassStudentItem>`（L106-108）
- 无需调用 `listStudentIds`，已有更丰富的 `listStudents` 接口

---

### 6. 监考数据源（MonitorController 真提供的数据）

**端点路径** (`MonitorController.java` L31-35):
```java
/** 监考大屏总览：实时人数统计 + 答题进度 + 异常高亮（前端轮询，建议 10-30s 间隔） */
@GetMapping("/overview")
public ApiResponse<MonitorOverviewResponse> overview(@PathVariable Long examId) {
    return ApiResponse.success(monitorService.overview(examId));
}
```

**响应结构** (`MonitorOverviewResponse.java`):
```java
@Data
public class MonitorOverviewResponse {
    private Long examId;
    private String examTitle;
    private Integer examStatus;  // 0 未开始 1 进行中 2 已结束 3 已批改 4 已发布成绩
    private int totalStudents;   // 已进入考试的学生总数
    private int onlineCount;     // 在线人数（心跳窗口内有活动）
    private int offlineCount;    // 离线人数（进行中但心跳已过期）
    private int submittedCount;  // 已交卷人数
    private int abnormalCount;   // 异常学生数（严重度≥2 行为事件）
    private int totalQuestions;  // 试卷题目总数
    private List<MonitorStudentItem> students;  // 学生明细列表
}
```

**学生明细** (`MonitorStudentItem.java`):
```java
@Data
public class MonitorStudentItem {
    private Long studentId;
    private String studentName;
    private String status;  // ONLINE / OFFLINE / SUBMITTED
    private int answeredCount;   // 已答题数
    private int progressPercent; // 进度百分比 0-100
    private boolean abnormal;    // 是否异常高亮
    private long abnormalEventCount;  // 异常事件数
    private Integer maxSeverity;    // 最高异常严重度（1 低 2 中 3 高）
    private LocalDateTime lastAbnormalTime;
}
```

**核实结论**:
- ✅ 提供：在线/离线/已交卷人数、逐学生进度、异常高亮统计
- ❌ **不提供**「实时」推送（需前端轮询，建议 10-30s 间隔）
- ❌ **不提供** Grafana 图表（只给入口链接）

---

### 7. 行为日志查询维度与 severity 取值

**端点路径** (`BehaviorLogController.java`):
| 方法 | 路径 | 说明 |
|-----|------|------|
| GET `/api/exams/{examId}/behavior-logs` | 分页筛选（按学生/事件类型/严重度） |
| GET `/api/exams/{examId}/behavior-logs/timeline` | 单个学生的行为时间线 |

**严重度枚举** (`SeverityLevel.java`):
```java
public enum SeverityLevel {
    LOW(1, "低"),    // 常见正常波动，如单次切屏
    MEDIUM(2, "中"), // 可疑但未达严重，如切屏达到次数阈值
    HIGH(3, "高");   // 系统级异常或屡次可疑行为
}
```

**核实结论**:
- 查询维度：按考试 + 可选按学生/事件类型/严重度组合过滤
- 时间排序：均按 `event_time` 升序
- Severity 取值：1=低、2=中、3=高（存储口径）

---

### 8. Grafana 面板地址

**证据文件** (`observability-runtime-evidence.md` L15):
- Grafana: `admin/admin`
- 数据源 uid: **`prometheus`**
- 面板 uid: **`exam-online-overview`**

**Docker 配置** (`docker-compose.observability.yml`):
- Grafana 端口：**3000**
- 容器名：`exam-grafana`

**核实结论**:
- Grafana 访问地址：`http://localhost:3000`
- 面板链接：`http://localhost:3000/d/exam-online-overview/exam-online-overview?ds=prometheus`
- 数据源 uid: `prometheus`

---

### 9. 教师越权校验

**权限注解** (`ExamController.java` L44-46):
```java
@RestController
@RequestMapping("/api/exams")
@RequirePermission("exam:manage")
```

**类级注释** (L41-42):
```
类级 {@code @RequirePermission("exam:manage")}（权限点已预置：TEACHER/ADMIN 均有）；
教师间水平越权由 Service 层 owner 校验兜底。
```

**核实结论**:
- ✅ 后端有 `@RequirePermission("exam:manage")` 垂直拦截
- ✅ 教师间水平越权由 Service 层 `owner` 校验兜底
- ⚠️ 前端不做「隐藏按钮即安全」的假设，需注释说明

---

## 三、意外发现与接口缺口

### 意外发现
1. **缺考标记时机**: `force-end` 和自然结束的缺考标记都在 `STATUS_IN_PROGRESS → STATUS_ENDED` 迁移时触发（`absenceService.markAbsence()`）
2. **监考轮询建议**: 后端注释明确建议「前端轮询，建议 10-30s 间隔」
3. **班级学生列表**: 已有 `listStudents(id)` 返回完整学生信息（含学号/姓名/入班时间），无需调用 `listStudentIds`

### 接口缺口
- **无缺口**: 所有需要的接口均已存在
- **openapi.yaml**: 若落后于后端代码，需停下回报重新 `gen:api`

---

## 四、实施边界确认

### 允许新增/修改
- ✅ `frontend/src/pages/**`（新增班级、考试、监考相关页面）
- ✅ `frontend/src/components/**`（新增考试表单、状态标签、确认弹窗等）
- ✅ `frontend/src/hooks/**`、`utils/**`、`constants/**`（新增本阶段所需）
- ✅ `frontend/src/router/**`（仅新增考务路由与菜单项）
- ✅ `frontend/src/api/**`（仅当后端契约已更新时重新 `gen:api`）
- ✅ `frontend/src/**/__tests__/**`（新增 vitest 用例）

### 禁止改动
- ❌ `src/main/**`、`src/test/**`、`pom.xml`、`openapi.yaml`、`docker/**`、`spec/**`
- ❌ `com.exam.clazz` 包（项目硬约束）
- ❌ 阶段 19/20 已交付的拦截器/守卫/认证页/题库组卷页（复用不改写）

---

## 五、关键实现约束

1. **状态机权威在后端**: 前端只展示当前状态与可用操作，不自行推算下一状态（代码处写注释说明）
2. **force-end 必须知情确认**: 弹窗文案含「该动作会触发缺考标记」关键词
3. **监考准实时轮询**: 用 `@tanstack/vue-query` 轮询，刷新间隔写成常量并在界面标注「每 10s 刷新」
4. **不重做观测图表**: Grafana 已有面板，前端只给只读入口链接
5. **防作弊配置只渲染后端已有字段**: 不新增前端专有配置项
6. **越权不靠前端隐藏**: 后端校验才是边界，前端注释写明「隐藏按钮不是安全边界」

---

## 六、轮询间隔常量建议

```typescript
// frontend/src/constants/monitor.ts
export const MONITOR_POLLING_INTERVAL_MS = 10000; // 10s
export const MONITOR_POLLING_HINT = '准实时轮询，每 10s 刷新';
```

---

## 七、状态映射 Constants

```typescript
// frontend/src/constants/examStatus.ts
export const EXAM_STATUS_MAP = {
  0: { label: '未开始', color: 'default' },
  1: { label: '进行中', color: 'processing' },
  2: { label: '已结束', color: 'error' },
  3: { label: '已批改', color: 'warning' },
  4: { label: '已发布', color: 'success' },
};
```

---

## 八、Grafana 链接规范

```vue
<a href="http://localhost:3000/d/exam-online-overview/exam-online-overview?ds=prometheus" 
   target="_blank">
  查看监控面板（面板由阶段 11/16 交付，前端不重做同口径图表）
</a>
```

---

**报告生成时间**: 2026-09-19  
**核实人**: AI Agent  
**基线 HEAD**: `feature/add-performance-deepening-readwrite`
