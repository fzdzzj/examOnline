# 提案：门禁命令与验收判据的单一来源（工程性变更 E2）

> 性质：工程性变更。来源：`better-harness` 评审（provider=qoder，窗口 2026-08-22 ~ 2026-09-21）发现 #2、#3、#5、#6。
> 前置：`add-agent-context-routing`（E1）建立"去哪儿查"的路由；本提案填上 E1 里标明"待回填"的唯一门禁命令，并把验收从常量换成判据。
> **本提案分两段，时序不同**：B-1（判据化，纯文档）应立即插入；B-2（构建与工具链）必须排在阶段 23 收尾之后或隔离工作树执行。

## Why

一个 agent 要验证自己的改动，得先猜出"该跑哪条命令"；跑完还要猜"这个绿算不算过关"。仓库里两个答案都有多个版本。

### 已核实的事实（逐条可复算）

**1）"怎么跑门禁"在仓库里至少有四种互斥说法，其中两种在同一份文档里前后矛盾。**

| 出处 | 说法 | 位置 |
|---|---|---|
| `README.md:12` | `mvn clean install` | 根入口 |
| `spec/changes/IMPLEMENTATION_STATUS.md:5` | `mvn -o test jacoco:report` | 被指定为"以本文件为准"的状态文件 |
| 5 份提示词 | "**不要用 PATH 里的 `mvn`**（版本错）" | `add-frontend-skeleton-auth/agent-prompt.md:107` + 4 份归档（`grep -c` 精确计数 5 个文件各 1 处） |
| `docs/指导Agent交接文档.md` §6.1（`:173-184`） | 手搓 `java.exe -classpath plexus-classworlds...  Launcher -o test`，硬编码仓库外的 JDK 与 Maven 安装目录及 `multiModuleProjectDirectory` | 交接文档 |
| 同一文档 §6.2（`:186-189`） | "直接 `mvn spring-boot:run`（dev profile）即可，不需要再设任何环境变量" | **与 §6.1 自相矛盾**（§6.1 的理由正是 PATH 里的 mvn 不能用） |

§6.1/§6.2 的前后矛盾是本次评审一手核读发现的，不是推断。

**2）机器绑定进了版本库，而它依赖的东西不在版本库里。**

- `.mvn/maven.config` 全文只有两行：`-s` 与一个指向仓库根的**绝对盘符路径**下的 `maven-settings.xml`（tracked，`git ls-files .mvn` 唯一命中）；
- `maven-settings.xml:8` 把 `<localRepository>` 指向仓库根下一个**被 .gitignore:7 排除**的 `.m2-repo/`（`git ls-files | grep -c m2-repo` = 0）；其注释自陈原因："本机 Maven 全局 settings.xml 将 localRepository 指向不可写的……导致构建失败。此处覆盖为工程内可写的 `.m2-repo`"；
- `pom.xml:267` 把一个依赖的版本选型理由写成"**已在本地 .m2-repo 离线可用**"；
- 仓库**没有 Maven wrapper**（`git ls-files | grep -i mvnw` = 0，`.mvn/` 下仅 `maven.config`）。

后果：门禁"能跑"依赖一棵既不在版本库、也不在新克隆里的本机目录树；表现就是 19–23 每个阶段提示词都要重抄一段启动器命令行——**每个阶段重做一次环境搭建**。

**3）验收数字是常量，而那些常量已经不可能满足。**

- 5 份进行中提示词的正文写着"后端全量测试基线 **210 全绿**，本阶段结束时必须仍是 210""基线不绿就停下回报"（`add-frontend-teacher-authoring/agent-prompt.md:18`、`add-frontend-exam-admin:16`、`add-frontend-student-taking:17`、`add-frontend-post-exam:16`、`add-frontend-skeleton-auth:31,67`），每份顶部再挂一段手写更正"下文所有「210」一律读作 **218**"；
- 同一常量的其它版本：`docs/指导Agent交接文档.md:8` 首屏 213；`spec/README.md:76,91` 记 213→218；`spec/changes/IMPLEMENTATION_STATUS.md:11` 记 268；提交信息 `7f104ee` 记 262、`da5b579` 记 273；
- 本次实测：`target/surefire-reports` 中 mtime 属 2026-09-21 的 **46** 个报告类求和 = `Tests run: 276, Failures: 0, Errors: 0, Skipped: 1`；把 2 个 09-20 的残留报告一起算会得到 **285 → 不做 `clean` 连本地产物都会给出错数**。

后果：照提示词执行的 agent 面对一个**不可能满足**的判据，两条出路都坏——误报回归，或回去"凑到 210"（删/禁用例），而后者正是 `spec/README.md` 与提示词自己反复设防的行为。

**4）"BUILD SUCCESS"不含质量语义，而声明的门禁有一道根本不会失败。**

- `pom.xml:335` `failOnError=false`、`:364` `failOnViolation=false`；JaCoCo 只有 `prepare-agent`(`:312`) 与 `report`(`:319`)，**没有 `check`**；`:45` 与 `:326` 明写这是刻意的渐进策略（"查出缺陷仍然 BUILD SUCCESS，缺陷只进报告不算账"）。**本提案不推翻该策略**，主张的是没有任何一处把它换成可执行判据。
- `frontend/package.json:16` `"type-check:check": "npm run type-check:app:check & npm run type-check:config:check"`（`:15` 的 `type-check` 同形）——单个 `&` 是 shell 后台符，因此 app 侧 `vue-tsc` 失败不进入脚本退出码；`:23` 的 `precommit:check` 又用 `&&` 把它串成一道看起来完整的门禁，而**没有任何 tracked 机制调用它**（`git ls-files` 对 husky/lint-staged = 0 命中）。进行中 5 份提示词恰恰把 `lint:check / type-check:check / vitest` 写成开工与收尾门禁。
- 历史代价（tracked 提交信息原文）：`7f104ee` 记 `46d7004`"提交了 `metrics.recordLockWait / recordLockContention` 的调用点，却漏掉了这两个方法与对应指标的定义，因此**该提交本身编译不过**"，同段还记 `docker-compose.yml` 既有编排被整份覆盖、`AuditLogService` 编译错误、行为上报接口全部 500；`spec/changes/IMPLEMENTATION_STATUS.md:17-19` 记"再往前 HEAD 本身编译不过"、此前 `@SpringBootTest` 全线 101 errors。`da5b579` 记 `b0eb18e` 的提交信息"机制与数值都记错过一次"，处理方式是不改写已提交历史、改以状态文件为准——**于是 `git log` 与真相分叉，且分叉点无法从 message 发现**。
- 会话侧同向：被采出的 Episode 全部 `acceptanceEvidenceCeiling=lead`，全总体 `withReviewedRelevantCheck=0`、`withResultSignal=0`。

### 期望状态

1. **一条命令、一个出处**：验证后端改动只需要查一处，且那条命令不依赖仓库外的路径；
2. **验收是判据不是常量**：`Failures=0 且 Errors=0`、`Skipped=1`（契约导出开关，属设计使然）、用例总数不得少于本阶段开工时的实测记录；
3. **每轮验收留下与 revision 绑定的记录**，沿用本项目 tasks.json 的"— 证据："惯例，而不是治理文档里的裸常量；
4. **声明出来的门禁必须能够失败**。

## What Changes

### B-1（纯文档；插在两个阶段**之间**执行）

> 时序约束：B-1 会改进行中阶段的提示词本身。某个阶段已派发、子 agent 正在执行时**不得改写该阶段的提示词**——那会让验收边界在执行中途变化，正是本项目 `交接文档:170` 记过的"门禁跑在工作区而非 HEAD"同一类错误。B-1 只能在阶段之间落地。

1. **基线常量改判据**：删除 5 份进行中提示词顶部的"一律读作 218"式更正段，把正文判据换成上面第 2 条；同步更正 `docs/指导Agent交接文档.md:8` 首屏与 `spec/README.md:29,76,91` 的常量表述（历史记录保留，但改写为"该数值描述的是 `<短 revision>` 那次执行"）。
2. **§6.1 与 §6.2 的矛盾就地收口**：§6.1 保留为"本机历史坑与当时的绕行办法"，并明确"仓库自有命令见 E2 回填位"；§6.2 的 `mvn spring-boot:run` 表述在其与 §6.1 的冲突解决前加一条注记。
3. **5 处"不要用 PATH 里的 mvn"** 改指向同一条仓库自有命令的引用点（命令本身在 B-2 落地前保持"待定"，见 E1 的 R2 Scenario）。

### B-2（构建与工具链，须排在阶段 23 之后或隔离工作树）

4. **加入 Maven wrapper**（`mvnw`、`mvnw.cmd`、`.mvn/wrapper/`），让"用哪个 Maven"由仓库决定；首次解析 distribution 需联网 → **列为需单独授权的前置**。
5. **`.mvn/maven.config` 去绝对路径**：settings 改为仓库内可解析的形式；`maven-settings.xml` 的 `localRepository` 改为可被环境变量覆盖或仓库相对（**实施时必须实测确认 Maven 解析 `maven.config` 中相对路径的基准目录**，不得凭猜测写死）。
6. **前端门禁可失败**：`frontend/package.json:15,16` 的单个 `&` 改为 `&&`；`precommit:check` 要么被本能力域的判据明确引用为"收尾必跑"，要么删除——**不留看起来像门禁、实际是装饰的脚本**。
7. **验收记录规范化**：把"命令 + 该次真实输出 + 当时短 revision"写成本阶段 `tasks.json` 证据字段的强制格式（不新增文件、不新增副本）。

### 明确不做

不把 SpotBugs/Checkstyle/JaCoCo 翻成阻断（尊重 `pom.xml:45` 的渐进策略）；不引入 CI 平台、不改远端配置；不新增 git 钩子（属跨边界动作，需单独授权）；不改 `src/main`、`src/test` 的任何业务代码与测试用例。

## Impact

### 受影响的规范

- `spec/specs/agent-harness/spec.md` — 追加 4 条 `ADDED`（唯一门禁命令 / 验收判据绑定 revision / 声明的门禁必须可失败 / 构建配置的机器绑定不得入库）。

### 受影响的文件（写入边界）

- B-1：`spec/changes/add-frontend-*/agent-prompt.md`（5 份进行中）、`docs/指导Agent交接文档.md`（`:8`、§6.1）、`spec/README.md`（`:29,76,91`）
- B-2：`.mvn/maven.config`、`maven-settings.xml`、新增 `.mvn/wrapper/**` 与 `mvnw`、`frontend/package.json`（仅 `:15,16` 两行）
- **不碰**：`src/**`、`pom.xml`（wrapper 不需要改 pom）、`schema.sql`、`openapi.yaml`、`docker/**`

### 与进行中阶段 19–23 的冲突（必须先看这段）

- `spec/README.md:29` 规定 19–23"一律不改后端（`src/main`、`src/test`、`pom.xml` 零改动）"。B-1 只改提示词与文档，**不违反**；B-2 改 `.mvn/`、`maven-settings.xml`、`frontend/package.json`——前两者不在禁改清单内，但**它们决定 19–23 的验收怎么跑**，`frontend/package.json` 更是前端系列的地盘。
- 因此：**B-1 在下一个阶段派发之前插入**（当前 5 个阶段全部受益，且消除"第三次误判环境"），阶段执行中不动该阶段提示词；**B-2 排在阶段 23 收尾之后**，或在独立工作树完成后由指导 agent 用一次全量门禁验证再合并。`spec/README.md:19` 的"同一工作树不得并行两个子 agent"对两段同样适用。

### 用户影响 / API 变更

- 无端点、无契约变化；`openapi.yaml` 不受影响。B-2 改变的是"如何验证"，不改变被验证的行为。

### 需要迁移

- [ ] 数据库迁移（**无**）
- [x] 构建配置变更（`.mvn/maven.config`、`maven-settings.xml`、新增 wrapper；`.m2-repo` 的归属需明确）
- [x] 文档更新（提示词 5 份 + 交接文档 + spec/README + agent-harness 规范）
- [ ] 用户沟通：若 `localRepository` 路径变化，需告知现有 `.m2-repo/` 是否保留

## 时间线评估

- **B-1：约半天**（纯文档 + 一次全量门禁以确认真实基线）；
- **B-2：约 1 天**（wrapper + settings 去绑定 + 前端脚本 + 一次从新克隆的实测），其中首次联网解析依赖的时间不可预估，**不得先承诺"离线可复现"**。

## 风险

- **离线库不可复现（最高风险）**。去掉 `-o` 或换机后需联网解析依赖，涉及网络与磁盘授权。缓解：本提案只承诺"命令不再依赖仓库外路径"，**不承诺**"任何机器零准备可跑"；`.m2-repo` 缺失时的表现必须是明确的前置提示而非编译错误。
- **`localRepository` 迁移可能造成磁盘翻倍**（旧 `.m2-repo/` 与新位置各一份）。缓解：实施前先量 `.m2-repo` 体积、保留原目录不删、给出回滚路径；本提案不授权删除任何缓存目录。
- **Maven 相对路径解析基准不确定**。`maven.config` 里相对路径按执行目录还是工程根解析，属需实测项；写错会让门禁在子目录执行时静默失败。缓解：验收场景要求"在仓库根与在 `frontend/` 子目录各跑一次"。
- **判据化会削弱"测试只增不减"的自动可见性**（不再有裸数字摆在文档首屏）。缓解：验收记录格式强制包含该次 `Tests run` 数值，收尾时与开工记录直接比较。
- **改 `frontend/package.json` 与前端系列并发改同一文件会冲突**。缓解：B-2 排到阶段 23 之后；若必须提前，只在独立工作树改并由指导 agent 单独合并这一处。
- **B-2 内部不做串行耦合**：任务 4（wrapper）需联网授权，若该授权迟迟不给，任务 5（`.mvn`/`maven-settings.xml` 去绝对路径）与任务 6（前端 `&` → `&&`）仍应独立完成——它们各自消除一处确定性缺陷，不以 wrapper 为前提；只有"唯一门禁命令"的回填依赖 wrapper 的最终结论。
- **不做的事**：不为了"有门禁"而新造一份数字副本——那会重演本提案要修的缺陷。
