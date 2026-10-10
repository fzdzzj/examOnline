能力缺口三层对照 · 零引用扫描证据（确定性脚本，脚本已删）
revision: 608adf5 (main)
脚本: D:\code\examOnline-r2-script\refscan.mjs（仓库外临时目录，用后即删）
方法: 解析 frontend/src 全部 173 个 .vue/.ts（含 __tests__）的 import {…} from '@/api/axios' 多行/as别名/type前缀 + 词法 tokenizer 统计 SDK 函数本地绑定全仓真实使用
命令: node refscan.mjs > refscan-full.txt
判定口径: 零引用 = 连测试都未以 SDK 导入引用（强口径）

==== U-3 复核：GET /api/exams 新参数消费面 ====
types.gen.ts line 1550-1560 Page2Data.query = { page?, size?, title?, status? }
前端消费: teacher/exams/index.page.vue:272-290 query { page,size,title:debouncedTitle||undefined,status:statusFilter??undefined }, queryKey [pageNum,pageSize,debouncedTitle,statusFilter]
结论: title/status 均被消费，无契约/消费缺口。

==== O-2 证据：addQuestion(单题入卷) 零引用 ====
grep -r '\baddQuestion\b' frontend/src（命中仅 sdk.gen.ts:249 定义、index.ts 重导出、测试 mock 键与 not.toHaveBeenCalled 负断言）
test files: paperBatchAdd.spec.ts (vi.fn mock / not.toHaveBeenCalled), paperDetailTable.spec.ts (vi.fn mock) —— 均非 SDK 真实调用
papers/[id].page.vue onPick 自 add-paper-batch-add-questions 改走批量 addQuestions（当前文件无 addQuestion 实引）
结论: 无一次 SDK 实引 -> O-2 观察项(第1轮漏记)
