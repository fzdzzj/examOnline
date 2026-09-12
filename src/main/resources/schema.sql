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
