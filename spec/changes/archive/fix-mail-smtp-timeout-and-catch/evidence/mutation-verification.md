# 变异验证：fix-mail-smtp-timeout-and-catch

判据：变异先红后绿——注入变异 → 用例确定性变红（击杀）→ 复原 → 用例回绿。全程留证本目录。

## m1：还原只捕 MessagingException 形态（击杀）

- **变异内容**：`MailService.sendResetCode` 的 `catch (MessagingException | MailException e)` 收窄回 `catch (MessagingException e)`（卡面指定变异：还原旧捕面形态）。diff 见 `m1.diff`（13 行，恰一行 catch 差异，与实施笔 41ebad3 逐字互逆）。
- **预期**：`mailSendExceptionIsConvertedToBusinessException` 变红（MailSendException 裸穿），失败形态与先红逐字同形。
- **实测**（`m1-run.log`）：`Tests run: 3, Failures: 1, Errors: 0, Skipped: 0` + BUILD FAILURE；失败行 `Unexpected exception type thrown, expected: <com.exam.common.BusinessException> but was: <org.springframework.mail.MailSendException>`（MailServiceTest:75），与先红（`01-red-run.log`）同形。
- **复原**：`git checkout HEAD --`（实施笔 41ebad3 版本）→ 复跑 `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` + BUILD SUCCESS（仓库外 `D:\code\examOnline-measure\fix-mail-smtp-timeout-and-catch\m1-restore-run.log`，判定行经本条登记）。

## 等价变异体排查

- **捕 `Exception` 兜底拓宽**（`catch (Exception e)`）：在既有测试面上与 `MessagingException | MailException` 不可区分（两路径同码同文案）——预案中等价变异体；因变异环节未实际注入该形态，**不强杀也不声称已测**，按提案 §技术注记预警口径如实登记为"已知等价形态，若人工改写至此形态需 review 把关"。
- **MessagingException 臂无独立运行期用例**（如实登记，两次尝试后裁撤）：①jakarta Mail 对 "not-an-email" 在 `helper.setTo` 处并不抛（宽松解析，实测 nothing was thrown），天然触发不可靠；②在 mock MimeMessage 的 `addRecipient` 处显式注入打桩，与 Spring `MimeMessageHelper` 内部实际委派路径不匹配，同样空转。裁撤论证：multi-catch 摘掉 `MessagingException` 即受检异常未捕获的**编译错误**（编译器守护，无需运行期护栏）；catch 体语义（码/文案）已由 MailException 臂用例覆盖——两臂共享同一 catch 子句体。裁撤过程与论据在测试类 javadoc 留痕。

## 结论

- m1 确定性击杀；红绿链完整：先红（捕面缺陷存在性：`expected BusinessException but was MailSendException`）→ 修复转绿 → m1 还原击红 → 复原回绿 → 全量门禁 641/0/0/1（`full-clean-test-summary.txt`）。
- 原始全量日志留存于仓库外 `D:\code\examOnline-measure\fix-mail-smtp-timeout-and-catch\`（red-run.log / green-run.log / m1-run.log / m1-restore-run.log / full-clean-test.log），本目录为判定行裁剪版。

## 过程事故留痕（无实质影响）

实施笔未提交时曾误用 `git checkout HEAD -- MailService.java` 做变异还原（M1 案的顺序是先提交实施笔再变异），导致修复版被还原为原始版、绿验证转红——发现后逐字重落修复（Edit 三处：import/javadoc/catch）、复跑 5/5 全绿确认无漂移，随后按正确顺序提交实施笔 41ebad3 并重做变异留证（本文件所录 m1.diff/run.log 均为提交后干净形态：恰 13 行 catch 单行差异）。教训与 M1 案一致：变异必须在实施笔入库后进行，`git checkout HEAD --` 语义是"还原到 HEAD"而非"撤销我的修改"。
