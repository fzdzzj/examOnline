# 证据索引：add-prod-profile-enforcements（安全专项发现 3+4 · prod profile 强制项）

> 门禁唯一命令：仓库根 `mvnw.cmd clean test`（带 clean，未用 `-o`）。本目录所有日志均为当次原样输出，
> 未经手工编辑；`06` 绑定产生它的 revision（见 `06-full-gate-summary.log` 内的构建行与下方 §06 记录）。

## 01 先红（旧形态复现）

`01-tdd-red-old-form.log` —— 回退实施笔的 `MailService`/`AuthProperties` 并删除
`ProdAdminPasswordGuard`，`-Dtest='ProdAdminPasswordGuardTest,MailServiceTest'`：

- 红形态为**编译红**（新测试引用不存在的 `ProdAdminPasswordGuard`、三参 `MailService` 构造），
  `[ERROR] BUILD FAILURE`；
- 行为红（断言级红）由 §03 变异以同形补证——编译红只证明"未实施即不绿"，不单独充当行为证据。

## 02 定向绿（实施后）

`02-targeted-green-impl.log` —— `-Dtest='ProdAdminPasswordGuardTest,MailServiceTest,AuthIntegrationTest'`：
26/26 绿（含 `AuthIntegrationTest` 找回密码全流程回归：test 档显式
`allow-log-fallback: true` 保住日志兜底路径，证明门禁只切断 prod 明文泄漏、未破坏开发/测试可跑通性）。

## 03 变异击红（行为级）

`03-red-mutation-guard-never-triggers.log` —— `scripts/mutation-check.sh` 注入
`stillDefault = false;`（启动闸永不触发）：`ProdAdminPasswordGuardTest#defaultPasswordFailsStartup`
确定性失败（`Tests run: 1, Failures: 1`，断言级红，非编译红），`BUILD FAILURE`。

## 04 复原回绿

`04-green-restored.log` —— 五步脚本自动复原后同用例 `Tests run: 1, Failures: 0`，`BUILD SUCCESS`。
先红后绿闭环。

### 等价变异登记（如实登记，不强杀）

- 把 `defaultPasswordFailsStartup` 的豁免分支（`allowDefaultPassword=true` 直接 return）改成同样
  fail-fast：**等价变异**——豁免路径由独立用例 `explicitExemptionBypasses` 单独钉住，改此分支会让该用例红
  而不是让目标用例红；不影响启动闸本身的判别力，不额外强杀。
- 把 `MailService` 兜底分支的 WARN 日志从"不含 code"改回含 code：日志内容不在断言可达面内（断言只看
  抛出的 `BusinessException` 与"未泄漏验证码"的可观测面），属**不可判别变异**（等价）——该约束靠
  javadoc + 代码评审钉住，此处如实登记不伪造红绿。

## 05 范围外自查

`05-out-of-scope-diff.txt` —— `git diff main --stat` 针对范围外清单（`schema.sql`、`docker/mysql/`、
`openapi.yaml`、`pom.xml`、`frontend/`、`docker/`、`AuthIntegrationTest`、`application-prod.yml`）
**输出 0 字节**（`OUT_OF_SCOPE_DIFF_LINES=0`，零 DDL、零契约/前端/编排改动）。
分支总 diff 12 文件全在 proposal 白名单内（三件套 + spec/README + 2 主类 + 配置 + 三档 yml + 2 测试类）。

## 06 全量门禁

`06-full-gate-summary.log`（尾部 8 行，含构建结论）：
`mvnw.cmd clean test` → `Tests run: 683, Failures: 0, Errors: 0, Skipped: 1` + `BUILD SUCCESS`。

- baseline 前后值：`.github/ci/gate-baseline.json` `backendTests` **676 → 683**（+7：`ProdAdminPasswordGuardTest`
  五用例 + `MailServiceTest` 两新用例），与门禁实数同笔；
- `frontendTests` 908 / `openapiPaths` 97 核对不动（本卡零前端、零契约改动）。

## 停止条件核验（未触发）

- **fail-fast 与部署流程冲突**：prod 编排 `ADMIN_PASSWORD` 为 `:?` 必填 → 新库初始创建即强口令，启动闸
  不会拦下正常部署；仅拦"存量库仍是公开默认口令"的裸奔状态，豁免旋钮 `ADMIN_ALLOW_DEFAULT_PASSWORD=true`
  给出一次性过渡路径（登录改密 → 移除豁免重启复核），见 proposal §二。
- 门禁全绿、无 CI 红、零 DDL、零重构级方案。
