# spec-delta：add-question-image

涉及域：question-bank、frontend（作答/回顾渲染归 frontend 域表述，对齐填空案先例）。

## 域：question-bank

### MODIFIED Requirement: 题目管理
新增 Scenario：
- Scenario: 上传题干图片 —— 教师（question:manage 权限）POST /api/question-images
  单文件上传：扩展名白名单（png/jpg/jpeg/gif/webp）+ 魔数校验防伪装 + 单文件 ≤5MB；
  服务端生成 UUID 文件名（不采纳客户端文件名），存储成功返回 `{url}`（相对 URL
  `/images/{uuid}.{ext}`）。
- Scenario: 上传校验拒绝 —— 非白名单扩展名 / 魔数不匹配 / 超大文件，均 4xx 带明确
  message，不落盘；无 question:manage 权限 403。
- Scenario: 题干图片下载 —— GET /images/{filename} 公开只读（随机文件名即凭据，
  不可枚举）：文件名白名单正则防路径穿越，按扩展名回 Content-Type，查无文件 404；
  既有快照消费方（判分/导出/监考）对快照新增 imageUrl 字段零回归。
- Scenario: 建题带图 —— 题目创建/编辑请求 imageUrl 可选（≤512、须以 `/images/`
  开头或为空，任意外链 URL 400）；与题型正交，五种题型均可选配；响应回显。
- Scenario: 组卷快照携带图片 —— 试卷快照/考试快照/个人快照的题目对象序列化携带
  imageUrl（无图题目为 null），既有字段映射零改动；既有无图题目全链路（组卷/发布/
  拉卷/判分/回顾）逐字零回归。

## 域：frontend

### MODIFIED Requirement: 题库管理界面
新增 Scenario：
- Scenario: 题干图片录入 —— 题目编辑表单加图片上传区（选择→上传中→预览→移除）；
  上传成功回填 imageUrl 随表单提交，与后端校验口径一致的本地预校验与错误提示。

### MODIFIED Requirement: 在线考试作答界面
新增 Scenario：
- Scenario: 题干图片作答渲染 —— 拉卷题目对象含 imageUrl 时作答卡渲染图片
  （题干文本下方，加载失败显示占位不阻塞作答）；imageUrl 为 null 时渲染与既有
  形态逐字一致。

### MODIFIED Requirement: 查分页逐题回顾入口与弹层
新增 Scenario：
- Scenario: 逐题回顾与错题本题干图 —— 回顾页/错题本对含图题目渲染题干图片，
  无图题目零回归；题库列表与题目选择器保持纯文本（不渲染图片）。

### 既有约束引用（不重复正文）
- 断言有效性纪律（decorativeAssertionGuard 词法护栏）适用于本变更全部新增测试。
- SDK 按既有代码生成方式接入，新端点与 DTO 字段自动携带。

## 契约

- openapi.yaml：新增 POST /api/question-images 端点（paths 78→79，只增不减）；
  题目创建/编辑/响应 DTO 加 imageUrl 字段（可选，≤512，/images/ 前缀）；
  下载端点 GET /images/** 不进契约（paths-to-match /api/** 既有口径）。
- gate-baseline.json：后端 surefire 与前端 vitest 用例数按实测同批上调，
  openapiPaths 79。
