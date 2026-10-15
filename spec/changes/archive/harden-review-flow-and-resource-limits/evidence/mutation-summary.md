# 变异验证总结（m1–m4）：harden-review-flow-and-resource-limits 阶段 2

纪律：注入违规 → 确认变红 → 复原（SHA256 与注入前一致）→ 重跑回绿。各变异单独留证见
m1-review-alert.txt / m2-user-export-batch.txt / m3-pdf-sanitize.txt / m4-classes-loading.txt；此处汇总四组关键数字。

| 变异 | 注入点 | 击红断言 | 注入后退出码 | 复原 SHA256 与注入前一致 | 回绿退出码 |
|---|---|---|---|---|---|
| m1 | ReviewHandleModal.vue 抽掉调分 warning Alert | 调分场景断言 Alert 缺失（expected null not to be null） | 1（1 failed / 6 passed） | 是（2E26B9D1…） | 0（7/7） |
| m2 | UserImportExportService 恢复一次性 selectBatchIds | >500 ID 用例分批调用次数 3 次实为 1 次（Wanted 3 but was 1） | 1（1 failed） | 是（3809BD8C…） | 0（1/1） |
| m3 | ScoreExportService 抽掉 PDF 清洗调用 | 含 Emoji 时 PDF 导出抛 BusinessException（AIOOBE: Index 128515） | 1（1 error） | 是（79B3EA92…） | 0（1/1） |
| m4 | classes/index.page.vue 抽掉 :loading 绑定 | 词法护栏缺失 + 在途无 ant-btn-loading | 1（2 failed / 1 passed） | 是（24FD850F…） | 0（3/3） |

结论：4 组变异全部先红后绿、复原哈希一致、回绿通过——四类加固均被真实捕获，非装饰性护栏。
