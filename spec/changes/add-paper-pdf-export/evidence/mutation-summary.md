# 变异验证总结（m1–m2）：add-paper-pdf-export 阶段 2

纪律：注入违规 → 确认变红 → 复原（SHA256 与注入前一致）→ 重跑回绿。各变异单独留证见
m1-ownership-guard.txt / m2-pdf-sanitize.txt；此处汇总两组关键数字。

| 变异 | 注入点 | 击红断言 | 注入后退出码 | 复原 SHA256 与注入前一致 | 回绿退出码 |
|---|---|---|---|---|---|
| m1 | PaperService.getOwnedPaper 抽掉 OwnershipGuard.assertOwner | 非归属教师导出 expected 403 but was 200 | 1（1 failed） | 是（6B4570B7…） | 0（1/1） |
| m2 | PaperExportService.sanitizePdfText 抽掉增补平面→'?'分支 | 清洗直测 expected <含??> but was <含😀𠀀> | 1（1 failed） | 是（9F9F8338…） | 0（1/1） |

结论：2 组变异全部先红后绿、复原哈希一致、回绿通过——越权门禁与 PDF 字符清洗均被真实捕获，非装饰性护栏。
