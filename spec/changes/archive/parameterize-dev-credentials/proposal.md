# 提案：dev 凭据参数化收口与规范固化（前后端优化组合·提案⑫，P3）

## Why

勘察把方向定在「dev 凭据可被环境变量覆盖、不裸写字面量」。**现状核实**（本提案立项时逐文件复核，结论以文件为准）：

- `application-dev.yml`：DB（主/从）、Redis、SMTP、JWT 各项**已是** `${ENV:fallback}` 形态，未设环境变量时用本地默认值——组合 spec 对本提案的原表述（「application-dev.yml 明文默认口令参数化」）对这份文件而言是**已完成状态，不是待办**；
- `application.yml`：RabbitMQ 口令、JWT（空 fallback + 生产 fail-fast）、admin 初始口令同样已是可覆盖形态；`application-prod.yml` 的 DB/SMTP 无默认值（缺变量即失败），符合生产语义。

**真正的缺口在 `docker-compose.yml`**：MySQL 主/从两处 `MYSQL_ROOT_PASSWORD` 是裸字面量——整个 dev 编排里仅剩的不可覆盖凭据。此外「凭据必须可覆盖」目前只是事实状态，没有任何规范或自查守住，后续新增配置项可能裸写回归。

**范围修正声明**：组合 spec 对本提案的原表述与文件现状不符，以本提案为准；方向不变（安全·凭据可覆盖），范围收窄为下述三项。

## What Changes

1. `docker-compose.yml` 的 `MYSQL_ROOT_PASSWORD`（主/从两处）改为 `${MYSQL_ROOT_PASSWORD:-<现默认值>}` 形态——compose 原生语法，默认值不变，**不设变量时开箱行为完全不变**；
2. 盘点并核实全部 `application*.yml` 凭据项的参数化形态（已就位，作为规范固化的证据基线，不是待办；不为「看起来做了事」去碰达标文件）；
3. 新建 `dev-config` 能力域：把「dev 凭据一律可被环境变量覆盖、新增凭据不得裸写字面量、生产凭据不得带默认值」固化为规范，并配可机械执行的自查命令（防新增裸凭据回归）。

## Impact

### 受影响的规范
- `spec/specs/dev-config/spec.md` — ADDED：开发环境凭据参数化（新能力域，收尾合入时创建目录，同 frontend 惯例不预建空壳）。

### 受影响的文件
- `docker-compose.yml`（两处 `MYSQL_ROOT_PASSWORD`）
- `spec/specs/dev-config/`（收尾时创建）

### 需要迁移
- [ ] 数据库迁移
- [ ] 业务 API

## 时间线评估

小：约 0.5 天。

## 风险

- **开箱体验是硬约束**：不设任何环境变量时，docker-compose 起容器、后端 dev 启动、口令匹配的行为与现状完全一致——改完必须以「干净环境起一遍 dev」验证；
- 本变更**不删除、不更换任何默认值**（换默认口令是行为变更，须另行决策）；
- `application*.yml` 已达标，不在改动范围；
- 生产 profile 语义零变化（fail-fast 不动）。
