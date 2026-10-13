# add-question-image：题干图片支持

## 背景与必要性

成熟产品差距分析三案串行（2026-10-13 用户裁决）的第二案：填空题（第一案）已闭环
（main=97b260e），本案先行，题库 Excel 批量导入导出（第三案）殿后——导入模板依赖
本案终态的题干形态，顺序不可倒置。

当前题目仅纯文本题干（`questions.content`），数学公式截图、生物/几何图表、阅读材料
配图类题目无法组卷，属内容表达力硬缺口（与填空题同源的差距分析结论）。

## 现场核证基础（HEAD=97b260e，2026-10-13 指导主agent 现场核证）

- 后端**零文件上传基础设施**（MultipartFile/multipart 源码零命中）；
  `application.yml` 既有 `spring.servlet.multipart` max-file-size 20MB /
  max-request-size 50MB（未被消费，题干图远小于此）——**application*.yml 可完全零触碰**。
- 鉴权为拦截器模式：`AuthenticationInterceptor` 只拦 `/api/**`（auth/security 红线域），
  全仓无任何静态资源 ResourceHandler——`/api` 外路径天然不经鉴权，公开只读图片端点
  **无需改任何鉴权配置**。
- 题库角色控制为类级 `@RequirePermission("question:manage")`（RBAC 权限点）——
  上传端点复用该权限点，不新增权限点（零 RBAC 表变更）。
- 快照序列化集中 `PaperSnapshotService.serialize()`（手工 ObjectNode 逐字段映射，
  choices 的 null→putNull 模式即 imageUrl 的扩展位）；个人快照以考试快照 paperJson
  为底本复用同一序列化（`PersonalPaperService`）——**只改一处序列化点，三种快照全部携带**。
- 判分消费 `GradingPaperReader` 手工 readTree 取字段；成绩导出（Excel/PDF）仅消费
  `question.content()` 文本——图片不进导出，判分/导出天然零回归（消费方对快照新增字段的
  宽容性由执行侧核证并留护栏用例）。
- 前端零上传组件；题干渲染点已盘点：作答卡（QuestionAnswerCard 插值纯文本）、逐题回顾、
  错题本、组卷预览、批改工作台、题库列表/题目选择器（紧凑表格）。
- 考试壳 SW `shouldCache`：`/api/` 一律 network-only（红线不动）；静态扩展名
  （png/jpg 等）命中既有缓存策略——题干图走既有静态缓存口径，**swCore 零改动**，
  断线时题干图随静态缓存可用，与「答案本地保存」口径一致、不构成离线考试。
- dev 编排仅基建容器（后端 dev 本地跑），dev compose 零触碰；vite dev proxy 与 prod
  web Nginx 目前仅代理 `/api`——`/images` 需受控各加一条（既有文件受控修改白名单）。

## 设计裁决

1. **数据形态：questions 加 `image_url VARCHAR(512) NULL` 单列单图**（相对 URL
   `/images/{uuid}.{ext}`），与题型正交（五种题型均可选配，不与 content 耦合）；
   多图、富文本编辑器、content 内嵌标记明确不做（proposal 边界，将来另行立项）。
   schema.sql 同批加列（AGENTS.md 约定 3 实体-建表双向一致）；存量库走
   `docker/mysql/migrations/2026-W42-add-question-image-url.sql`（幂等头注对齐 W41
   先例：复跑仅 1060 重复列名错误为预期），**dev 应用不随本案自动生效**——脚本入库
   即完成变更交付，实际应用时机由用户另行裁决派发（AGENTS.md 约定 4）。
2. **上传链路**：新增 QuestionImageController `POST /api/question-images`
   （multipart 单文件；类级 `@RequirePermission("question:manage")` 复用题库权限点；
   扩展名白名单 png/jpg/jpeg/gif/webp + 魔数校验防伪装 + 单文件 ≤5MB
   `@Value("${exam.question-image.max-size-mb:5}")`；服务端生成 UUID 文件名，
   绝不采纳客户端文件名；存储目录 `@Value("${exam.question-image.dir:./data/question-images}")`
   环境变量可覆盖）→ 返回 `{url}`。上传与题目保存解耦：前端先传图得 URL，再随
   创建/更新表单提交 imageUrl（天然支持「先上传预览再保存」交互）。
3. **下载链路**：`GET /images/{filename}` 公开只读端点（**置于 /api 外**，天然不经
   AuthenticationInterceptor，auth/security 零改动；随机 UUID 文件名即 capability
   URL，不可枚举；文件名白名单正则防路径穿越；按扩展名回 Content-Type；404 兜底；
   springdoc paths-to-match /api/** → 不进 OpenAPI 契约，paths 仅 +1 上传端点）。
4. **快照连带**：`PaperSnapshotService.serialize()` 新增 imageUrl 序列化行
   （null→putNull，对齐 choices 模式），既有字段映射行零改动；考试快照/个人快照自动
   携带；判分/导出/监考等消费方零回归。
5. **契约**：QuestionCreateRequest/QuestionUpdateRequest/QuestionResponse 加
   imageUrl（可选、≤512、须以 `/images/` 开头或为空——服务端校验防任意外链注入）；
   openapi.yaml 按离线路径 A 重导出（paths 78→79，仅增上传端点）；
   `gate-baseline.json` 双端用例数与 openapiPaths 同批上调（只增不减）。
6. **前端**：QuestionForm 加图片上传区（上传中/失败态、预览、移除）；渲染点裁决——
   **作答卡、逐题回顾、错题本、组卷预览、批改工作台渲染图片**；题库列表与题目选择器
   保持纯文本（紧凑表格）。SDK 按既有方式再生成。
7. **既有文件受控修改白名单**（白名单外既有文件零触碰）：`vite.config.ts`
   （dev proxy 加 `/images` 一条）、`.gitignore`（追加 `data/`）、
   `docker-compose.prod.yml`（app 服务 environment 加 QUESTION_IMAGE_DIR + 卷挂载；
   web Nginx 加 `/images` 反代——nginx conf 实际承载文件位置由执行侧现场核证后落于
   该处，同属白名单）、`schema.sql`（+1 列 + 注释）、`.github/ci/gate-baseline.json`
   （同批上调）、`openapi.yaml`（契约重导出产物）。
8. **边界（明确不做）**：孤儿图片文件不清理（删题/换图后旧文件留存，物理清理另行
   立项）；图片不进 Excel/PDF 导出（文本导出零改动）；不做缩略图/压缩（原图直存，
   零新依赖）；下载端点不做签名/过期（随机文件名即凭据，题库图片敏感度可接受）；
   上传不限流（题目管理低频操作，非核心链路）。

## 红线（零触碰）

- auth/security 包（含 WebMvcConfig/AuthenticationInterceptor——图片端点置于 /api 外
  的设计即为不触碰该域）、com.exam.clazz 包、ScoreService/MakeupScoreService、
  判分核心既有方法体（ExamGradingService/ObjectiveGradingService/SubjectiveGradingService）、
  `pom.xml`（零新依赖：Spring 内置 multipart + NIO 足够）、`application*.yml`
  （新配置全走 @Value 默认值 + 环境变量覆盖）、`.github/`（gate-baseline.json 同批
  上调除外）、swCore/swRegister（SW 策略零改动）、dev `docker-compose.yml`。
- `PaperSnapshotService.serialize()` 仅允许新增 imageUrl 序列化行，既有字段映射行
  零改动；既有五题型的判分/归一化/快照/导出行为逐字零回归。
- `spec/specs/` 既有基线零修改（spec-delta 只在 changes 目录）。

## 验收边界（三案串行的第二案）

- 全新链路可用：教师上传图片 → 建题带图保存 → 手动组卷/随机抽题 → 发布 → 学生拉卷
  看到题干图 → 作答 → 判分/逐题回顾/错题本正确展示；图片 URL 直接访问可取；
  既有无图题目（imageUrl 为 NULL）全链路零回归。
- 双端门禁全绿且用例数净增；契约 paths 78→79；变异自证≥3 项（如：撤魔数校验→伪装
  文件入库→红；撤文件名白名单→路径穿越→红；撤快照 imageUrl 行→拉卷丢图→红；
  撤教师权限→学生可上传→红）。
- W42 迁移脚本入库（幂等头注），dev 应用时机由用户另行裁决，不随本案收口。

## 停止条件

- 鉴权链核证发现 /api 外路径无法安全暴露（如存在拦截 /api 外路径的未知过滤器/
  Spring Security 依赖）——立即停止上报。
- SW 对 /images 的既有缓存策略与考试壳红线解释冲突（核证 shouldCache 静态扩展名
  实际行为后若判为踩线）——上报裁决，不硬凑。
- 快照消费方（GradingPaperReader/ScoreExport/Monitor 等）存在显式 record 严格反序列化
  （FAIL_ON_UNKNOWN_PROPERTIES）导致既有判分/导出回归且无法零改动兼容——停止上报。
- 任一红线需破坏才能实现——立即停止上报。
