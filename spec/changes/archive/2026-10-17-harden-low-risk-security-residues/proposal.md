# Proposal: 安全低危残差收尾治理 (harden-low-risk-security-residues)

## 1. 为什么（Why）

本项目此前已全面解决高危与中危缺陷，并完成了多轮横切加固与 Redis 降级演练。在安全专项首扫台账（`docs/功能待办清单.md` B.2）中尚余两项低危（Low）安全与资源面残差：

1. **`MessageController.listMine` limit 无上界（资源面残差）**：
   - 现状：`MessageController.list` 接收 `int limit` 参数（缺省 100），直接透传给 `MessageService.listMine(limit)`，通过 SQL `.last(limit > 0 ? "LIMIT " + limit : "")` 拼接；
   - 隐患：当客户端传入恶意超大值（如 `limit=1000000`）或非正数时，数据库将返回海量消息或无限制全表查询，带来高内存消耗、慢查询甚至 OOM 风险；
   - 目标：对入参实施边界钳制（`[1, 100]`），超过 100 自动截断为 100，小于等于 0 自动回退为 1，杜绝无界读取。

2. **生产环境 `/v3/api-docs` 契约明细未关闭（信息泄露面）**：
   - 现状：`application-prod.yml` 中虽然关闭了 `swagger-ui`（`enabled: false`），但 `api-docs.enabled` 仍配置为 `true`；且 `WebMvcConfig` 的登录鉴权拦截器仅拦截 `/api/**`，导致 `/v3/api-docs` 公开可访问；
   - 隐患：生产环境中未认证外部攻击者可直接拉取完整的后端 OpenAPI 规范 JSON，获知全站 API 路径、入参出参模型及包含管理员端点（如用户导入导出、强制踢人、复核调分）的详细 schema，构成内部拓扑与结构泄露面；
   - 目标：在 `application-prod.yml` 中将 `springdoc.api-docs.enabled` 默认关闭（`${API_DOCS_ENABLED:false}`），并通过词法护栏测试钉住。

本提案针对上述两项安全低危项集中收尾，彻底清空安全巡查发现台账中的代码缺陷。

---

## 2. 改什么（What）

### 2.1 站内消息查询上限保护 (`MessageController`, `MessageService`)
- 在 `MessageController.list` 与 `MessageService.listMine` 中对 `limit` 执行安全钳制：
  ```java
  int safeLimit = Math.min(Math.max(limit, 1), 100);
  ```
- 确保 SQL 拼接始终为 `LIMIT [1..100]`；
- 编写 `MessageServiceTest` 单元测试，覆盖正常传参、超大值截断（1000 $\to$ 100）、非正数回退（0/-1 $\to$ 1）及未读数与标记已读。

### 2.2 生产环境 OpenAPI 暴露面默认收敛 (`application-prod.yml`)
- 修改 `application-prod.yml` 中的 `springdoc.api-docs.enabled`：
  ```yaml
  springdoc:
    swagger-ui:
      enabled: false
    api-docs:
      enabled: ${API_DOCS_ENABLED:false}
  ```
- 新建词法护栏 `ProdApiDocsGuardTest`，断言 `application-prod.yml` 中 `api-docs` 与 `swagger-ui` 均默认关闭（禁止为裸 `true`）。

---

## 3. 影响能力域与规范

- `deployment`：生产环境 API 文档暴露面收敛规范；
- `exam-taking`：站内消息中心查询上限保护规范。

---

## 4. 零 DDL 与质量红线

- **零 DDL**：未新增、修改任何表结构，未改动 `schema.sql`，不添加任何 `docker/mysql/migrations/` 脚本；
- **契约稳定**：`openapi.yaml` 既有 97 个 paths 保持稳定；
- **测试零 @Sql**：测试基于 Mockito 与已有基础设施；
- **gate-baseline 只增不减**：全量后端用例数基线锁定上调。

---

## 5. 变异验证计划

- **m1（消息 limit 钳制移除）**：移除 `MessageService` 中的 `safeLimit` 钳制改回裸 `limit`，验证超大 limit 测试变红；
- **m2（生产 api-docs 默认关闭改回 true）**：将 `application-prod.yml` 的 `api-docs.enabled` 改回 `true`，验证 `ProdApiDocsGuardTest` 变红。
