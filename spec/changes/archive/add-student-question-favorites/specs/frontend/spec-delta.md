# frontend 规范变更

## ADDED Requirements

### Requirement: 学生题目收藏界面与交互 (add-student-question-favorites)

系统 SHALL 在学生端提供专属的「我的收藏」管理界面，并在答卷回顾页面提供收藏星标交互。

#### Scenario: 学生端我的收藏列表浏览与取消收藏

GIVEN 学生访问 `/student/favorites` 页面
WHEN 页面加载完成
THEN 系统渲染当前学生收藏的题目卡片列表（包含题干、题型标签、收藏时间与参考解析折叠面板）
AND 支持按题型筛选（单选/多选/判断/填空/简答）与关键字模糊搜索
AND 点击卡片上的「取消收藏」按钮后即时移出列表

#### Scenario: 答卷回顾中题目收藏状态回显与一键切换

GIVEN 学生查看考后答卷回顾页面
WHEN 页面初始化
THEN 系统通过批量接口检测并回显各题的收藏星标（已收藏高亮展示，未收藏镂空展示）
AND 点击星标图标可即时切换收藏状态，并在操作成功后更新本地视图状态
