# Proposal: 数据健壮性（Excel防注入与Unicode全角清洗）与交互防呆（三态分离与并发防抖锁）治理 (harden-data-sanitization-and-ui-states)

## 1. 为什么（Why）

系统边界与合理性专项巡查发现，除了核心权限与考务高危逻辑外，系统在数据进出边界清洗（Data Sanitization）和前端异常交互防护（UI States & Guarding）上存在 4 项具有典型安全与体验隐患的中度（Medium）缺陷：

1. **Excel 导出公式防注入缺失（M-01，安全防注入）**：`ExcelSheetWriter`、`ScoreExportService`、`UserImportExportService` 与 `QuestionImportExportService` 将用户可控内容（学生姓名、用户名、题干、选项、备注等）原样写入单元格。当字符串以 `=`, `+`, `-`, `@`, `\t`, `\r` 等字符开头时，Excel / LibreOffice 会将其解析为公式或 DDE 命令触发执行（CSV / Formula Injection），存在恶意代码执行与数据外发风险；
2. **Excel 导入 Unicode 全角空格清洗缺失（M-02，数据容错与健壮性）**：`QuestionImportExportService` 和 `UserImportExportService` 单行单元格提取时仅依赖标准 `String.trim()`，而 Java 原生 `trim()` 仅处理 ASCII `<= 0x20`，对中文全角空格 `\u3000` 及不换行空格 `\u00A0` 无法清除。用户从中文文档复制包含全角空格的题型（如 `　单选　`）、难度（`　易　`）、学号、班级名称时，会因脏字符导致校验整行报错拒绝，降低批量导入可用性；
3. **4 个主要页面缺失 U-1 失败三态分离（M-03，体验防呆）**：班级列表（`classes/index`）、题库列表（`questions/index`）、试卷详情（`papers/[id]`）、标签列表（`tags/index`）4 处 `useQuery` 未解构 `error`。接口遭遇 500、网络中断或权限拒绝时，数据回退为空数组/空对象，界面直接渲染为「暂无数据」空状态，将系统故障伪装为业务结论；
4. **题库批量删除缺乏 loading 与并发防抖锁（M-04，并发安全防呆）**：`questions/index.page.vue` 的批量删除虽然提供了 Popconfirm 二次确认，但在触发 `onBatchDelete` 异步请求期间未绑定 `:loading` 状态，也未加并发防抖锁，连击可发出并发重复请求，造成后端无谓的重复查询与并发竞态。

本提案针对上述 4 项中度缺陷集中立项治理，彻底补齐系统数据与交互边界。

---

## 2. 改什么（What）

### 2.1 Excel 导出公式防注入（`ExcelSheetWriter` / 导出服务）
- 在 `com.exam.score.support.ExcelSheetWriter` 增加公式防注入清洗方法：
  ```java
  public static String sanitizeFormula(String val) {
      if (val == null || val.isEmpty()) {
          return val;
      }
      char first = val.charAt(0);
      if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
          return "'" + val;
      }
      return val;
  }
  ```
- 在 `ScoreExportService`（学生姓名、学号、题干、备注等）以及 `UserImportExportService`（用户名、姓名等）输出单元格时对文本内容统一调用 `sanitizeFormula`，确保以敏感公式字符开头的文本被强制前置单引号以纯文本形式渲染。

### 2.2 Excel 导入 Unicode 全角空格与脏空白清洗（`QuestionImportExportService`, `UserImportExportService`）
- 在 `QuestionImportExportService` 与 `UserImportExportService` 的单元格读取 `cellText` 方法中，使用统一清洗逻辑，剥除首尾全部 Unicode 空白（涵盖半角空格、`\u3000` 全角空格、`\u00A0` 不换行空格及控制字符）：
  ```java
  private static String stripWhitespace(String text) {
      if (text == null) {
          return "";
      }
      return text.replaceAll("^[\\s\\u3000\\u00A0]+|[\\s\\u3000\\u00A0]+$", "");
  }
  ```
- 确保导入时即使用户文本含有首尾全角空格或 NBSP，解析器依然能精准匹配题型（如 `　单选　` 匹配 `SINGLE`）、难度、班级名称和学号。

### 2.3 4 个主要页面补齐 U-1 失败三态分离（前端）
- 涉及页面：
  1. `frontend/src/pages/(dashboard)/teacher/classes/index.page.vue`
  2. `frontend/src/pages/(dashboard)/teacher/questions/index.page.vue`
  3. `frontend/src/pages/(dashboard)/teacher/papers/[id].page.vue`
  4. `frontend/src/pages/(dashboard)/teacher/tags/index.page.vue`
- 实施口径：
  - 解构 `useQuery` 的 `error`；
  - 声明 `errorText`（或 `queryErrorText`）计算属性，遵循 U-1 范式：`error.value instanceof Error ? error.value.message : '...'`；
  - 模板中放置 `<Alert v-if="errorText" type="error" show-icon :message="errorText" :data-test="...-error" class="mb-3" />`；
  - 表格或空状态节点绑定 `v-else` 或 `v-else-if="!errorText"`，确保失败时绝不渲染为空表格或「暂无数据」。

### 2.4 题库批量删除 loading 与并发防抖锁（前端）
- 在 `frontend/src/pages/(dashboard)/teacher/questions/index.page.vue`：
  - 定义 `const batchDeleting = ref(false)`；
  - 为批量删除 `<Button>` 绑定 `:loading="batchDeleting"` 与 `:disabled="batchDeleting || selectedIds.length === 0"`；
  - 在 `onBatchDelete` 入口处增加防抖守卫：`if (batchDeleting.value) return; batchDeleting.value = true;`，并在 `finally` 块中复位 `batchDeleting.value = false;`。

---

## 3. 影响能力域与规范

- `score-management`：Excel 公式防注入清洗规范；
- `question-bank`：题目导入 Unicode 全角空格清洗容错规范；
- `authentication`：用户导入 Unicode 全角空格清洗与导出防注入规范；
- `frontend`：4 个关键页面失败三态分离与题库批量删除并发防抖锁规范。

---

## 4. 零 DDL 与红线声明

- **零 DDL**：未新增、修改任何表结构，未改动 `schema.sql`，不添加任何 `docker/mysql/migrations/` 脚本；
- **契约稳定**：`openapi.yaml` 既有 89 个路径与 schema 保持稳定，无需修改契约；
- **严禁测试使用 `@Sql`**：新测用例一律基于已有数据库基础设施；
- **双端门禁只增不减**：全量后端用例数基线锁定上调。

---

## 5. 变异验证计划

- **m1（Excel 导出公式防注入）**：抽掉 `ExcelSheetWriter.sanitizeFormula` 的前置单引号逻辑，验证导出含 `=1+1` 或 `@SUM` 敏感字符时用例断言变红；
- **m2（Excel 导入全角空格清洗）**：抽掉 `stripWhitespace` 对 `\u3000` / `\u00A0` 的剥除逻辑（退回标准 `trim()`），验证含有全角空格的题型导入测试断言变红（报错「题型必须为：单选/多选/判断/简答/填空」）；
- **m3（前端 4 页面失败三态分离）**：抽掉页面中 `errorText` 对应的 error Alert 渲染，验证查询失败场景下单测断言 Alert 缺失而变红；
- **m4（批量删除 loading 与防抖锁）**：抽掉 `batchDeleting` 并发锁，验证并发连续调用 `onBatchDelete` 时发生重复请求而测试变红。
