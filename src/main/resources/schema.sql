-- =============================================================
-- examOnline —— RBAC 五表建表脚本（add-project-skeleton）
-- 兼容 MySQL 8.0 与 H2(MySQL 兼容模式, 测试用)；全部幂等（IF NOT EXISTS）
-- 注：不使用 MySQL 专属 COMMENT 子句，保证 H2 测试环境可解析
-- =============================================================

CREATE TABLE IF NOT EXISTS users (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    username             VARCHAR(64)  NOT NULL,
    password             VARCHAR(100) NOT NULL,
    name                 VARCHAR(64)  NOT NULL DEFAULT '',
    email                VARCHAR(128)          DEFAULT NULL,
    status               TINYINT      NOT NULL DEFAULT 0,
    must_change_password TINYINT      NOT NULL DEFAULT 0,
    created_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted           TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS roles (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    code       VARCHAR(32) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    level      INT         NOT NULL,
    created_time DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_roles_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS permissions (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    code       VARCHAR(64) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    created_time DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS user_roles (
    id      BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    CONSTRAINT uk_user_roles UNIQUE (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
);

CREATE TABLE IF NOT EXISTS role_permissions (
    id            BIGINT NOT NULL AUTO_INCREMENT,
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    CONSTRAINT uk_role_permissions UNIQUE (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles (id),
    CONSTRAINT fk_role_permissions_perm FOREIGN KEY (permission_id) REFERENCES permissions (id)
);

-- 教师邀请码表（add-authentication）：管理员生成，教师凭有效码注册为 TEACHER
CREATE TABLE IF NOT EXISTS invite_codes (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    code         VARCHAR(32) NOT NULL,
    note         VARCHAR(128)         DEFAULT '',
    status       TINYINT     NOT NULL DEFAULT 0,  -- 0=有效 1=已作废
    used_count   INT         NOT NULL DEFAULT 0,  -- 已被用于注册的次数（管理员可见）
    created_by   BIGINT      NOT NULL DEFAULT 0,  -- 创建人用户 ID
    created_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted   TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_invite_codes_code UNIQUE (code)
);

-- =============================================================
-- 题库与组卷六表（add-question-bank）
-- 题型仅 4 类：1单选 2多选 3判断 4简答（其余题型已裁剪，见 docs/面试版实施方案.md §2.3）
-- =============================================================

-- 题目表：软删除（is_deleted），被组卷/快照引用后删除不影响历史
CREATE TABLE IF NOT EXISTS questions (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    type           TINYINT      NOT NULL,            -- 1单选 2多选 3判断 4简答
    content        TEXT         NOT NULL,            -- 题干
    choices        TEXT,                             -- 客观题选项 JSON 数组，如 ["选项A","选项B"]；判断/简答为 NULL
    correct_answer VARCHAR(512) NOT NULL,            -- 归一化答案：单选字母/多选升序字母列表/判断 T-F/简答参考答案
    score          DECIMAL(5,1) NOT NULL DEFAULT 5,  -- 默认分值（组卷可在试卷内覆盖，互不影响）
    difficulty     TINYINT      NOT NULL DEFAULT 1,  -- 1易 2中 3难
    analysis       TEXT,                             -- 答案解析
    created_by     BIGINT       NOT NULL,            -- 创建教师 ID（owner 校验依据）
    created_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted     TINYINT      NOT NULL DEFAULT 0,
    KEY idx_questions_created_by (created_by)         -- 教师个人题库列表
);

-- 标签表：扁平四类（学科/难度/题型/自定义），预留扩展 parent_id 升级树形
-- 不设 (name,type) 唯一键：软删后同名标签可重建，避免唯一键冲突，查重在 Service 层做
CREATE TABLE IF NOT EXISTS tags (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    name         VARCHAR(64) NOT NULL,
    type         VARCHAR(16) NOT NULL,               -- SUBJECT/DIFFICULTY/QUESTION_TYPE/CUSTOM
    created_by   BIGINT      NOT NULL DEFAULT 0,
    created_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted   TINYINT     NOT NULL DEFAULT 0,
    KEY idx_tags_type (type)
);

-- 题目-标签多选关联
CREATE TABLE IF NOT EXISTS question_tags (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    question_id BIGINT NOT NULL,
    tag_id      BIGINT NOT NULL,
    CONSTRAINT uk_question_tags UNIQUE (question_id, tag_id),
    KEY idx_question_tags_tag (tag_id)              -- 按标签筛题目
);

-- 试卷表：total_score 为教师申报总分，保存/生成快照时与各题分值之和校验一致
CREATE TABLE IF NOT EXISTS papers (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    title          VARCHAR(128)  NOT NULL,
    description    VARCHAR(512)           DEFAULT '',
    total_score    DECIMAL(5,1)  NOT NULL DEFAULT 0,
    question_count INT           NOT NULL DEFAULT 0, -- 题目数量（随组卷操作维护）
    status         TINYINT       NOT NULL DEFAULT 0, -- 0=草稿 1=已锁定（快照已生成）
    snapshot_id    BIGINT                 DEFAULT NULL, -- 当前生效快照 ID
    created_by     BIGINT        NOT NULL,
    created_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted     TINYINT       NOT NULL DEFAULT 0,
    KEY idx_papers_created_by (created_by)
);

-- 试卷-题目关联：number 试卷内题号（1 起连续）；score 试卷内分值（覆盖题目默认分，互不影响）
CREATE TABLE IF NOT EXISTS paper_questions (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    paper_id    BIGINT       NOT NULL,
    question_id BIGINT       NOT NULL,
    number      INT          NOT NULL,
    score       DECIMAL(5,1) NOT NULL,
    CONSTRAINT uk_paper_question UNIQUE (paper_id, question_id),
    CONSTRAINT uk_paper_number UNIQUE (paper_id, number),
    KEY idx_paper_questions_question (question_id)
);

-- 试卷快照：组卷/抽题结果锁定后的序列化副本（题目/答案/分值/顺序），只写不改
CREATE TABLE IF NOT EXISTS paper_snapshots (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    paper_id       BIGINT       NOT NULL,
    paper_json     LONGTEXT     NOT NULL,           -- 快照 JSON（含题目内容/归一化答案/分值/题号顺序）
    question_count INT          NOT NULL DEFAULT 0,
    total_score    DECIMAL(5,1) NOT NULL DEFAULT 0,
    version        INT          NOT NULL DEFAULT 1, -- 预留版本号：同一试卷不重复生成，当前固定 1
    created_by     BIGINT       NOT NULL,
    created_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_paper_snapshots_paper (paper_id)
);

-- =============================================================
-- 考试管理两表（add-exam-management）
-- 状态机：0未开始 → 1进行中 → 2已结束 → 3已批改 → 4已发布（docs/需求决策记录.md §4.3）
-- 状态流转一律走乐观锁 CAS（UPDATE ... WHERE status=? AND version=?），保证并发安全
-- =============================================================

-- 考试表：绑定试卷/课程/班级，设定时间窗与个人时长；发布后学生可见，到点自动进入进行中
CREATE TABLE IF NOT EXISTS exams (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    title              VARCHAR(128) NOT NULL,
    description        VARCHAR(512)          DEFAULT '',
    paper_id           BIGINT       NOT NULL,            -- 绑定试卷（发布时生成考试快照，快照与试卷从此解耦）
    course_id          BIGINT                DEFAULT NULL, -- 课程 ID（课程实体后续阶段提供，先存 ID）
    class_id           BIGINT                DEFAULT NULL, -- 班级 ID（同上）
    start_time         DATETIME     NOT NULL,            -- 时间窗起点：定时发布的触发点（服务端时间为准，§1.1）
    end_time           DATETIME     NOT NULL,            -- 时间窗终点：到达即自然结束
    duration_minutes   INT          NOT NULL,            -- 个人答题时长：学生点击"开始考试"后倒计时（§7.9，阶段 5 消费）
    allow_late_minutes INT          NOT NULL DEFAULT 0,  -- 允许迟到分钟数（超过开始时间多久仍可进入）
    status             TINYINT      NOT NULL DEFAULT 0,  -- 0未开始 1进行中 2已结束 3已批改 4已发布
    published          TINYINT      NOT NULL DEFAULT 0,  -- 0=未发布（学生不可见） 1=已发布（发布即生成考试快照）
    force_end          TINYINT      NOT NULL DEFAULT 0,  -- 1=教师提前结束标记（强制交卷在阶段 5 按最后自动保存处理，§1.5）
    anti_cheat_config  TEXT,                             -- 防作弊配置 JSON（切屏检测/禁复制等开关，阶段 5 消费）
    snapshot_id        BIGINT                DEFAULT NULL, -- 考试快照 ID（发布时回填）
    version            INT          NOT NULL DEFAULT 0,  -- 乐观锁版本号：CAS 状态流转的并发护栏
    created_by         BIGINT       NOT NULL,            -- 创建教师 ID（owner 校验依据）
    created_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_deleted         TINYINT      NOT NULL DEFAULT 0,  -- 软删（§7.7：仅未开始可删，历史可追溯）
    KEY idx_exams_created_by (created_by),               -- 教师考试列表
    KEY idx_exams_status (status)                        -- 定时任务扫表：按状态 + 时间窗筛选待推进考试
);

-- 考试快照：发布时一次性序列化"考试配置 + 完整试卷内容"（题目/归一化答案/试卷内分值/题号顺序），只写不改
-- 之后答题/判分/回看一律读快照，试卷或题目再怎么改都不污染历史场次（§10.10）
CREATE TABLE IF NOT EXISTS exam_snapshots (
    id           BIGINT   NOT NULL AUTO_INCREMENT,
    exam_id      BIGINT   NOT NULL,
    exam_json    LONGTEXT NOT NULL,   -- 考试配置副本（时间窗/时长/迟到容忍/防作弊配置）
    paper_json   LONGTEXT NOT NULL,   -- 试卷内容副本（结构与 paper_snapshots.paper_json 一致，复用同一序列化）
    version      INT      NOT NULL DEFAULT 1,  -- 一场考试只生成一次快照，当前固定 1
    created_by   BIGINT   NOT NULL,
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_exam_snapshots_exam UNIQUE (exam_id)  -- exam_id 唯一：发布是快照生成的唯一时机
);

-- =============================================================
-- 在线考试与交卷三表（add-exam-taking）
-- 交卷可靠性（docs/需求决策记录.md §1.1-§2.3）：
--   三重幂等 = 防重表(exam_submit_dedups) + uk_exam_student 唯一索引 + SETNX 分布式锁；
--   三路竞态 = 手动 / 前端倒计时归零 / 后端定时兜底，共享答卷状态机 CAS（进行中→已交卷仅一次）；
--   削峰落库 = 交卷发 MQ 消息，消费者批量落库（rewriteBatchedStatements），落库成功才 ack。
-- =============================================================

-- 答卷表：学生进入考试即建行（个人快照/开始时间随行锁定），交卷只做状态 CAS 迁移，答案由 MQ 消费者异步落库
CREATE TABLE IF NOT EXISTS exam_submissions (
    id             BIGINT   NOT NULL AUTO_INCREMENT,
    exam_id        BIGINT   NOT NULL,
    student_id     BIGINT   NOT NULL,
    start_time     DATETIME NOT NULL,            -- 个人开始时间：点击"开始考试"才计时（§7.9，服务端记录）
    deadline_time  DATETIME NOT NULL,            -- 个人截止：min(开始+时长, 考试 end_time)；后端兜底扫描依据
    submit_time    DATETIME              DEFAULT NULL,
    submit_type    TINYINT               DEFAULT NULL,  -- 提交来源：1手动 2前端归零 3后端兜底
    paper_json     LONGTEXT              DEFAULT NULL,  -- 个人快照：题序/选项乱序进入时锁定，刷新/重进不换题（§3.4）
    answers        LONGTEXT              DEFAULT NULL,  -- 最终答案 JSON（questionId→答案）；NULL=尚未落库（补发扫描依据）
    status         TINYINT  NOT NULL DEFAULT 1,  -- 答卷状态：1进行中 2已交卷 3已批改（判分阶段消费）
    version        INT      NOT NULL DEFAULT 0,  -- 乐观锁版本号：状态 CAS 护栏
    created_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_exam_student UNIQUE (exam_id, student_id),  -- 三重幂等之一：一人一场至多一条答卷
    KEY idx_submissions_sweep (status, deadline_time),        -- 兜底扫描：按状态筛进行中/已交卷未落库
    KEY idx_submissions_exam_submit (exam_id, submit_time)
);

-- 交卷防重表：提交请求先查后插（uk 兜底并发插入），是三重幂等的第一道持久化闸；并发进入/重试路径据此快速幂等返回
CREATE TABLE IF NOT EXISTS exam_submit_dedups (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    exam_id       BIGINT   NOT NULL,
    student_id    BIGINT   NOT NULL,
    submission_id BIGINT   NOT NULL,
    submit_type   TINYINT  NOT NULL DEFAULT 1,
    created_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_submit_dedup UNIQUE (exam_id, student_id)  -- 同一场考试同一学生仅记录首次提交
);

-- 考试行为日志表：切屏/失焦/草稿冲突等事件只记录不处置（完整防作弊在阶段 7）
CREATE TABLE IF NOT EXISTS exam_behavior_logs (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    exam_id      BIGINT      NOT NULL,
    student_id   BIGINT      NOT NULL,
    event_type   VARCHAR(32) NOT NULL,           -- SWITCH_SCREEN / WINDOW_BLUR / DRAFT_CONFLICT ...
    event_data   TEXT                DEFAULT NULL,  -- 事件明细 JSON（离开时长/客户端时间等）
    severity     TINYINT     NOT NULL DEFAULT 1, -- 1提示 2警告 3严重
    event_time   DATETIME    NOT NULL,
    created_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_behavior_exam_student (exam_id, student_id),
    KEY idx_behavior_exam_time (exam_id, event_time)
);
