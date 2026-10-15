# 变异验证总览（m1–m4）：harden-data-sanitization-and-ui-states 阶段 2

纪律：注入违规 → 确认变红 → 复原（SHA256 与注入前一致）→ 重跑回绿。
各变异单独留证见 m1/m2/m3/m4 各文件；此处汇总四组关键数字。

| 变异 | 注入点 | 击红断言 | 注入后退出码 | 复原后 SHA256 与注入前一致 | 回绿退出码 |
|---|---|---|---|---|---|
| m1 | ExcelSheetWriter.sanitizeFormula 前置单引号整段抽掉 | 成绩导出姓名列 + 用户导出姓名/邮箱列以 =/@ 开头未前置 ' | 1（2 例失败，BUILD FAILURE） | 是（C39F3402…） | 0（BUILD SUCCESS） |
| m2-question | QuestionImportExportService.stripWhitespace 退回 trim() | 「　单选　」题型导入 imported=0 | 1（BUILD FAILURE） | 是（FF76E462…） | 0（BUILD SUCCESS） |
| m2-user | UserImportExportService.stripWhitespace 退回 trim() | 全角空格学号/班级名导入 imported=0 | 1（BUILD FAILURE） | 是（2CF80D5E…） | 0（BUILD SUCCESS） |
| m3 | 4 页面查询错误 Alert 渲染条件改 v-if="false" | 4 页面查询失败用例断言 Alert 缺失 | 1（4 failed / 7 passed） | 是（4 文件逐一 MATCH） | 0（11/11） |
| m4 | onBatchDelete 入口 batchDeleting 守卫抽掉 | 在途连击 batchDelete 被调用 2 次（expected 1） | 1（1 failed / 7 passed） | 是（DF9D0CD1…） | 0（8/8） |

结论：5 组变异（m1、m2×2、m3、m4）全部先红后绿、复原哈希一致、回绿通过——
说明四类加固均被真实断言捕获，非装饰性护栏。
