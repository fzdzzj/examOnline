# spec-delta: frontend (add-question-duplicate-detection)

### Requirement: 题库管理界面

#### Scenario: 录题与编辑题干相似度预检与防呆提示 (ADDED)
- **WHEN**: 教师在录题或编辑题目弹层（`QuestionFormModal.vue`）中输入题干内容且字数达到阈值
- **THEN**: 界面 SHALL 在输入防抖或失焦时触发查重预检
- **AND**: 若检测到库内存在相似度 $\ge 60\%$ 的题目，界面 SHALL 显性展示 warning 提示及相似题目预览
- **AND**: 提示为知情确认，教师仍可继续确认保存，不强制阻断录入
