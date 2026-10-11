# MySQL 备份/恢复执行台账（BACKUPS.md）——「日期 × 环境 × 产物 × 演练」唯一真值

> 本文件是 `docker/mysql/backup/` 脚本在各环境**真实执行**的台账，对齐
> `docker/mysql/migrations/APPLIED.md` 的「无自动执行者 + 人工登记」纪律形态。
> 脚本入库 ≠ 备份已运行：**本表能查到的一行 = 一次真实执行过的备份或演练**。
> 归档/回报里的「已备份」声称仅作对照，不构成登记真值——登记真值来自对被备份库现场
> 执行备份/恢复的实测（命令 + 当次输出 + 短 revision），见目录 README「执行与登记纪律」。
> 本文件不写执行次数：计数以本表行数为准，新增一行的同一 commit 必须同笔（见「更新纪律」）。

## 更新纪律（执行 + 校验 + 登记三件套齐才算完成）

一次真实备份/恢复演练，**「执行 + 校验 + 登记本表」三件套齐才构成完成**：

1. **执行**：按目录 README 命令对目标库执行（备份 `backup.sh --env dev|prod`；
   恢复演练在隔离一次性容器 `restore.sh --dump ... --container ... --db ... --confirm`）；
2. **校验**：核对脚本输出 —— 备份：`sha256sum -c` 通过、产物存在；恢复演练：目标库表数
   与 dump 表数一致、抽样行验证一致；dev/prod 本体零写入（演练目标为隔离容器）；
3. **登记**：同笔更新本表对应行——日期、环境、文件名、sha256、演练结论、当时 commit。

## 登记表

| 日期 | 环境 | 文件名（`out/` 下） | sha256 | 恢复/演练结论 | 执行者 / commit |
|---|---|---|---|---|---|
| 2026-10-12 | dev 主库（exam-mysql-master） | `exam_online-20261012-013713.sql.gz` | `4d2a39ae47fafadb3b477c04e3ed07d2c8a72e91b8888de1f15a02a1176e53f7` | 恢复演练**走通**（一次性隔离容器 `backup-drill-restore-1` / 库 `restore_verify`）：sha256 校验 OK；表数三层一致 27=27=27（主库=恢复库=dump）；关键表行数 users15/exams11/questions25/papers12/exam_submissions8 全 MATCH；users 15 行逐值 MATCH、exams 11 行逐值 MATCH；dump 含 GTID 元信息（`SET @@GLOBAL.GTID_PURGED` + `-- Position`）| 执行子 agent / (实施笔后回填 commit) |
| 2026-10-12 | prod 编排库（exam-online-prod-mysql-master） | `exam_online-20261012-014353.sql.gz` | `67d4a5ac9a713602c7e037e0d313ff33014e6d5d98583f8a31b1cfd09a00c945` | prod 全链演练**走通**（全新卷起 prod 栈备份 → down -v 清卷重起 → 恢复进 prod 主库）：sha256 校验 OK；恢复后 26 表（=dump 26）；admin 行 id=1/username=admin/must_change_password=1；actuator/health/db 200；HTTPS 登录 200（accessToken/ADMIN）；dev 主从与 prod 本体全程零写入（恢复仅写于编排内 prod 主库演练目标） | 执行子 agent / (实施笔后回填 commit) |

## 说明

- 备份产物在 `docker/mysql/backup/out/`（gitignored，不入库）；本表只登记**文件名的
  basename + sha256 摘要**，不登记产物本体内容。
- 恢复演练一律在一次性隔离容器进行，dev/prod 库本体零写入；本表「演练结论」列如实记
  述是否走通、表数/抽样是否一致，绝不把「脚本提交」写成「已演练」。