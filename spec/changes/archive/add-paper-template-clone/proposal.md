# add-paper-template-clone：试卷模板与克隆复用

## 背景与必要性

`docs/examOnline需求规格说明书.md` L71「3.5 试卷模板库（保存/复用/版本管理）」；
候选池 B 节最后一项中优先功能（2026-10-15 用户裁决立案）。现状（2026-10-15 Explore
核证）：**未建成立**——PaperController 覆盖 CRUD/手动组卷/随机抽题/快照共 13 端点，
无任何复制/克隆/模板端点；schema 仅 papers/paper_questions/paper_snapshots，无模板表。
教师重复组卷只能从零开始，模板复用是组卷效率的最后一块短板。

关键解耦事实：**考试发放消费的是快照**（exam_snapshots.paper_json 复用 paper_snapshots
结构）而非 papers 本身——克隆/模板操作不触碰快照链路，对已发放考试零影响，这让
「复制卷」成为安全操作。

## 现场核证基础（2026-10-15 Explore 核证）

- PaperController（`com/exam/paper/controller/PaperController.java` L34-158）：13 端点
  （创建/分页/详情/更新/删除/加题/批量加题/移题/调序/抽题预览/抽题确认/生成快照/
  读取快照），类级 paper:manage；无克隆/模板端点。
- PaperService L45-58 关键约束：手动组卷、随机抽题、快照锁定、考试进行中锁定、
  owner 校验；L82-148 建卷与元信息更新；L166-254 手动组卷核心（加/移/调序/分值覆盖）
  ——复制逻辑的目标域。
- 快照语义（PaperSnapshotService L37-140）：组卷结果锁定后序列化落库，
  papers.status 置已锁定并回填 snapshotId；此后读取以快照为准。
- 考试-试卷关系：exams.paper_id 绑定；ExamService.create L70-101 经
  getOwnedPaper 校验归属后写入；ExamPaperLockService L37-66 进行中考试锁定。
- 表结构：papers/paper_questions/paper_snapshots（schema.sql L126-168）字段级已核；
  papers 有 status（草稿/已锁定）与 snapshotId。
- 前端：teacher/papers/ 列表页与编辑页已建（含随机抽题 Tab）。
- spec 归属：试卷管理在 question-bank spec（手动组卷 L198、抽题锁定与试卷快照）。
- 数字链：后端 461 / 前端 665 / openapiPaths 87（add-user-import-export 收口后基线）；
  本案新增 2 端点，paths 87→89，gate-baseline 三值同批上调。

## 设计裁决

1. **范围＝保存 + 复用 + 克隆；版本管理不做**：另存为模板（任意卷→模板）、从模板
   一键建卷、任意卷克隆（复制为新草稿卷）。愿景 3.5 的「版本管理」不建——快照
  机制已提供历史固化语义，模板更新=教师直接编辑模板后再用，多版本迭代属过度
  设计，列为边界。
2. **形态＝papers 表加 is_template 列（默认 0），不建独立模板表**：模板=打标的
   试卷。独立模板表需镜像 papers/paper_questions 两张表结构 + 双份复制逻辑，
   成本高；一列方案配合集中守卫，既有全部行为对非模板卷（is_template=0）零变化。
   模板卷允许编辑（教师调整模板，下次建卷用新态，符合直觉）。
3. **端点 2 个**：
   - `POST /api/papers/{id}/clone`：任意本人非删除卷 → 复制为**新草稿卷**
     （对模板卷调用＝从模板一键建卷，同一动作覆盖两场景）；
   - `POST /api/papers/{id}/save-as-template`：任意本人非删除非模板卷 → 复制为
     新模板卷（原卷保留）。
   paths 87→89。
4. **克隆语义**：复制 papers 元信息 + paper_questions 全量（题目/顺序/分值覆盖）；
   标题加「（副本）」后缀防混淆；新卷 owner=操作者；**产物永远是草稿态**（不复制
   status=已锁定与 snapshotId）——克隆锁定卷得到可编辑草稿，原卷及其快照不动。
5. **模板守卫三处（最小插入）**：模板卷 SHALL NOT 被选为考试卷（ExamService.create
   归属校验后追加模板拒绝）、SHALL NOT 生成快照（PaperSnapshotService 追加拒绝）、
   SHALL NOT 再 save-as-template（无意义嵌套）。守卫为**追加校验行**，既有行零改动。
6. **列表区分**：既有试卷分页端点加 isTemplate 过滤参数（默认 false=普通试卷视图，
   模板不出现；true=模板库视图）——既有调用方不传参行为不变（向后兼容）。
7. **个人模板**：owner 校验沿用（仅本人可见可用自己的模板）；跨教师共享模板库
   不建（涉及审核/可见性语义，列为边界）。
8. **权限**：paper:manage + owner 归属（对齐试卷域既有形态）。
9. **schema 变更**：papers 加 is_template 列（TINYINT DEFAULT 0，H2/MySQL 双兼容）
   ——走 schema.sql 唯一入口 + 同批实体字段（硬约定 1/3）+ 存量迁移脚本
   `docker/mysql/migrations/2026-W43-add-paper-template-clone.sql`（硬约定 4：
   提交≠已应用，应用由用户裁决）。
10. **前端**：试卷列表页操作列加「克隆」「另存为模板」按钮 + 「模板库」Tab
    （isTemplate=true 视图，操作列：使用〔即 clone〕/ 编辑 / 删除）；SDK 再生成；
    零新依赖。

## 红线（零触碰）

- 既有试卷 CRUD/手动组卷/随机抽题/快照全链路行为（PaperService/PaperSnapshotService
  既有行——守卫三处为追加校验行，其余零改动）；
- 考试发放与快照消费链路（exam_snapshots/exam_snapshots 读取侧零改动）；
- ExamPaperLockService 锁定语义；
- 题库域（question 表与 QuestionService 零改动）；
- pom.xml（零新依赖）、application*.yml、`.github/`（gate-baseline 三值上调除外）、
  spec/specs 既有基线、openapi.yaml 既有 paths（只增不减）。

## 验收边界

- 克隆：元信息+题目全量复制、副本后缀、草稿态产物、克隆锁定卷得可编辑草稿且
  原卷快照零影响、owner 归属克隆者、非 owner 403。
- 另存为模板：原卷保留（原状态不变）、模板入库（is_template=1）、对模板卷再
  save-as-template 被拒。
- 从模板建卷：模板库「使用」→ 新草稿卷含全部题目、模板本身不动。
- 守卫：模板被选为考试卷被拒（可观察错误，不产生 exam）、模板生成快照被拒。
- 列表：默认视图无模板、isTemplate=true 仅模板、既有分页/排序/过滤行为零回归
  （护栏：既有试卷用例全绿）。
- 双端门禁全绿净增；paths 87→89；变异自证 ≥3 项（候选：撤模板考试卷守卫→模板可
  被选为考试卷→红；撤草稿态产物→克隆带出锁定态→红；撤全量复制→题目丢失→红）；
  schema is_template 列 + 实体同批 + 硬约定自查全绿（表级双向一致命令）。
- 存量迁移脚本 W43 提交≠已应用（应用由用户裁决，收口不代做）。

## 停止条件

- 模板守卫三处无法以追加校验行实现（需重构 ExamService/PaperSnapshotService
  既有逻辑行）——停止上报。
- is_template 列 H2/MySQL 双兼容或实体映射受阻（硬约定 3 自查不过）——停止上报。
- 发现既有等价复制/模板能力（重复建设）——停止上报。
- 任一红线需破坏才能实现——立即停止上报。
