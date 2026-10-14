# add-user-import-export：用户/学生 Excel 批量导入导出

## 背景与必要性

`docs/需求决策记录.md` L163-165 已决策「学生账号来源：管理员 Excel 导入 + 初始密码，
预留教务同步接口」；候选池 B 节「用户 / 学生 Excel 批量导入」行（中优先，2026-10-14
用户裁决立案并扩展导出面：导入 + 导出 + 用户域其他导出）。现状（2026-10-15 Explore
核证）：**未建成立**——AdminController 仅有 kick 等单账号端点，无任何用户/学生批量
或导出端点；`docs/功能待办清单.md` B 节该行为真实差距。

③ add-question-import-export 已交付同构形态（三端点 + 逐行容错 + 列定义枚举 +
SXSSF 流式），本案直接迁移复用其模式，不重新发明。

## 现场核证基础（2026-10-15 Explore 核证）

- 权限参照：`AdminController`（`com/exam/auth/controller/AdminController.java` L28-40）
  类级 `@RequireRole(ADMIN)` + 方法级 `@RequirePermission("user:manage")`——新端点
  沿用同一权限形态。
- 账号底层：`UserService`（`com/exam/user/service/UserService.java` L45-65）
  `existsByUsername`/`insert` 查重建号入口；L119-149 密码 BCrypt 处理与登录上下文
  组装——初始密码加密复用既有 BCrypt 路径。
- 班级挂接：`classes` + `user_class` 表已建（schema.sql L349-363，转班只更新
  user_class.class_id）；`ClassController` L80-107 已有 joinStudent/transfer/
  listStudents 端点语义可参照。
- ③ 复用形态：`QuestionImportExportController`（import/export/import-template
  三端点）+ `QuestionImportExportService`（逐行解析报错、正确行独立事务入库、
  行数上限、返回 {imported, failed, errors}、导出 SXSSF 流式列口径与模板一致）
  + `QuestionImportColumn` 列定义枚举——本案整体迁移此模式。
- 数字链：后端 452 / 前端 660 / openapiPaths 84（add-wrong-question-practice 收口
  后基线）；本案新增 3 端点，paths 84→87，gate-baseline 三值同批上调。
- 无新表：复用 users/classes/user_class，schema.sql 预期零改动（若执行侧发现挂接
  需要的索引缺失，上报裁决而非自行加列加表）。

## 设计裁决

1. **范围＝学生批量导入 + 模板下载 + 参数化用户导出**：
   - `POST /api/admin/users/import`（multipart xlsx）：批量建**学生**（学号/姓名/
     邮箱可选/初始密码/班级名称）；
   - `GET /api/admin/users/import-template`：下载导入模板（表头与导入解析、导出
     列严格一致）；
   - `GET /api/admin/users/export`（参数化 scope=student|teacher|all，可选 classId
     过滤学生）：覆盖「学生名单（按班级）/ 教师名单 / 用户全量」三类导出诉求，
     一个端点三种口径，不为每类各开端点。
   「其他一些东西」收敛为用户域内三类；班级列表导出**不做**（班级数量小，管理页
   可见）；其他域（考试/答卷等）导出不在本案范围。
2. **权限＝ADMIN + user:manage**（对齐 AdminController 既有形态与决策记录「管理员
   Excel 导入」）；教师导本班名单**不建**（教师已有 listStudents 查看面，开放导出
   属权限扩张，列为边界）。
3. **复用③形态**：`UserImportColumn` 列定义枚举（固定列序表头）、逐行独立事务容错
   （坏行跳过并逐行报告错误，正确行入库）、行数上限 1000、导入返回
   {imported, failed, errors}、导出 SXSSF 流式且列与模板一致。POI 依赖既有（③引入），
   pom 零新增。
4. **班级归属**：模板填**班级名称**，服务端按 classes 表名称解析挂接 user_class；
   班级不存在（或同名歧义）＝该行错误跳过，**不自动建班**（建班是独立管理动作）。
5. **重复账号**：`existsByUsername` 查重，重复行错误跳过**不覆盖既有账号**（防批量
   误改既有密码/姓名）；文件内学号重复同样跳过后出现者。
6. **初始密码**：模板必填列，BCrypt 入库（复用既有加密行）；不设默认密码常量
   （避免常量泄露面）；首次登录改密等策略不在本案（既有登录链路行为零改动）。
7. **导入角色固定 STUDENT**：模板不含角色列——批量建管理员/教师不在本案（防越权
   建号），教师账号走既有单建路径。
8. **导出口径**：对齐管理侧既有列表查询口径（软删/状态过滤与列表一致——具体列
   集执行侧核验既有列表实现后对齐，若不存在对应列表则以表定义+决策记录为准并在
   tasks.json 留证）；scope=student 输出班级归属列（多班以分号或主班口径，执行侧
   对齐 user_class 实际语义）。
9. **教务同步接口**：决策记录「预留」＝本案不做，列为边界。
10. **前端**：管理端用户管理页接入导入（上传+结果反馈：成功/失败计数与逐行错误）、
    模板下载、导出按钮（scope/班级选择）；SDK 再生成；零新依赖。

## 红线（零触碰）

- ③ 既有题目导入导出全链路（QuestionImportExport* 只参照不修改）；
- auth/user/clazz 既有端点与服务实现行（AdminController/UserService/
  ClassController 只调用零修改——班级挂接若需复用 joinStudent 语义，以调用或
  等价 SQL 新增实现，不改既有行）；
- 登录/密码校验链路（BCrypt 既有行、buildLoginUser）；RBAC 权限模型与既有权限点；
- schema.sql（预期零改动；确需索引/列变更＝停止上报，不自行改）；
- pom.xml（零新依赖）、application*.yml、`.github/`（gate-baseline 三值上调除外）、
  spec/specs 既有基线、openapi.yaml 既有 paths（只增不减）。

## 验收边界

- 导入：合法行入库（学生+班级挂接正确）、重复学号跳过、班级不存在跳过、缺必填/
  超行数上限/坏格式逐行或文件级报错、角色固定 STUDENT（导入行无法产生教师/管理员
  账号）、初始密码 BCrypt 入库且能以该密码登录。
- 导出：scope 三态输出正确人群、classId 过滤学生、列与模板一致、SXSSF 流式
  （既有大数据量护栏模式对齐）、软删/状态口径与列表一致。
- 模板：表头与导入解析、导出列三向一致（护栏用例锁定）。
- 权限：非 ADMIN 403、缺 user:manage 403、学生/教师 token 均不可达。
- 既有③用例与 auth/clazz 域既有用例零回归；双端门禁全绿净增；paths 84→87；
  变异自证 ≥3 项（候选：撤查重→重复覆盖既有账号→红；撤班级解析容错→不存在班
  自动建班→红；撤权限门控→教师可导入→红；撤角色固定→可建管理员→红）；
  schema.sql 零 diff（预期）复核确认。

## 停止条件

- 班级挂接无法在不改 clazz/auth 既有服务行的前提下实现（需改 joinStudent/
  UserService.insert 既有行）——停止上报。
- 导出口径与既有列表口径冲突且无法在不改既有行的前提下对齐——停止上报。
- 发现 users 相关已有等价导入/导出面（重复建设）——停止上报。
- schema.sql 需要变更（索引/列）才能支撑——停止上报，由用户裁决是否扩案。
