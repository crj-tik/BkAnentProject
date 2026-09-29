-- create-interview-subagent：AI 深访子 Agent 数据模型
-- 库：bk_interview（独立业务库，对齐各服务一库惯例）

CREATE DATABASE IF NOT EXISTS bk_interview DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE bk_interview;

-- 访谈案例主实体：五大场景 + 案例状态机 + 禁成功预设
CREATE TABLE IF NOT EXISTS interview_case (
    id BIGINT NOT NULL AUTO_INCREMENT,
    scene VARCHAR(32) NOT NULL COMMENT 'STORE_MANAGER|AGENT_DEAL|SECOND_HAND_PARTY|NEW_HOUSE_FIELD|COMMUNITY_EXPERT',
    case_status VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT 'won|active|lost|churned',
    respondent_role VARCHAR(32) NOT NULL COMMENT '受访人角色（按场景枚举）',
    objective VARCHAR(500) NOT NULL COMMENT '访谈目标一句话',
    division_name VARCHAR(64) NULL COMMENT '组织三级：事业部',
    region_name VARCHAR(64) NULL COMMENT '组织三级：大区',
    business_district VARCHAR(64) NULL COMMENT '组织三级：商圈',
    project_name VARCHAR(128) NULL,
    store_name VARCHAR(128) NULL,
    listing_name VARCHAR(128) NULL,
    respondent_name VARCHAR(64) NULL,
    no_success_preset TINYINT NOT NULL DEFAULT 0 COMMENT '禁成功预设：未成交/流失案例自动开启',
    must_collect_items JSON NULL COMMENT '必采清单（禁成功预设时启用）',
    creator_work_no VARCHAR(32) NOT NULL COMMENT '创建人工号（双入口对齐字段）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_interview_case_creator (creator_work_no),
    KEY idx_interview_case_scene (scene),
    KEY idx_interview_case_org (division_name, region_name, business_district)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 访前基础卡：动态字段 + 信息完整度 → 出题策略
CREATE TABLE IF NOT EXISTS interview_prep_card (
    id BIGINT NOT NULL AUTO_INCREMENT,
    case_id BIGINT NOT NULL,
    dynamic_fields JSON NOT NULL COMMENT '按场景的 6 个动态字段',
    completeness VARCHAR(8) NOT NULL DEFAULT 'LOW' COMMENT 'LOW|MEDIUM|HIGH',
    question_strategy VARCHAR(24) NOT NULL DEFAULT 'EXPLORE' COMMENT 'EXPLORE|DIRECTED_SUPPLEMENT|TIMELINE_FILL',
    background_material TEXT NULL,
    knowledge_snippets JSON NULL COMMENT '知识库拉取注入的片段',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_interview_prep_card_case (case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 题目（题目确认制）
CREATE TABLE IF NOT EXISTS interview_question (
    id BIGINT NOT NULL AUTO_INCREMENT,
    case_id BIGINT NOT NULL,
    outline_route VARCHAR(8) NOT NULL COMMENT 'T1-T8 提纲路由',
    seq_no INT NOT NULL COMMENT '预算序号',
    content TEXT NOT NULL,
    focus_label VARCHAR(64) NULL,
    risk_hint VARCHAR(255) NULL,
    confirmed TINYINT NOT NULL DEFAULT 0 COMMENT '题目确认制：只有确认题进入访谈',
    answer_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|ANSWERED|SKIPPED',
    probe_rounds INT NOT NULL DEFAULT 0 COMMENT '该题追问轮数',
    depth_limit INT NOT NULL DEFAULT 3 COMMENT '深度上限（核心题4-5，一般题2-3）',
    is_core TINYINT NOT NULL DEFAULT 0 COMMENT '核心题（前两题）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_interview_question_case (case_id, confirmed, seq_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 访谈会话（状态机 + 凭据 + 收尾锁）
CREATE TABLE IF NOT EXISTS interview_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    case_id BIGINT NOT NULL,
    mode VARCHAR(16) NOT NULL DEFAULT 'AI_LEAD' COMMENT 'AI_LEAD|ASSIST|FORM',
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT|QUESTIONS_CONFIRMED|IN_PROGRESS|CLOSING_LOCKED|COLLECT_PENDING|ARCHIVED',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁',
    reference_minutes INT NULL COMMENT '参考时长（仅控制题目预算）',
    engine_type VARCHAR(16) NOT NULL DEFAULT 'XIAOZHI' COMMENT 'XIAOZHI|XIAOFENG|COMMUNITY_26Q（P1 仅 XIAOZHI）',
    ticket_hash VARCHAR(128) NULL COMMENT '会话凭据 HMAC 哈希（仅放行本场 turn/monitor）',
    ticket_expires_at DATETIME NULL,
    closing_locked TINYINT NOT NULL DEFAULT 0 COMMENT '收尾持久锁：首次收尾后强制极短对等道别',
    risk_topic_counter JSON NULL COMMENT '风险主题跨轮计数（同类题全场≤2）',
    industry_brain_cache JSON NULL COMMENT '行业大脑场景包开台预读缓存（访中零跨服务）',
    entry_source VARCHAR(16) NOT NULL DEFAULT 'A2A' COMMENT 'A2A|FORM 双入口',
    creator_work_no VARCHAR(32) NOT NULL,
    started_at DATETIME NULL,
    closed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_interview_session_ticket (ticket_hash),
    KEY idx_interview_session_case (case_id),
    KEY idx_interview_session_creator (creator_work_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 话轮（幂等落库）
CREATE TABLE IF NOT EXISTS interview_turn (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    turn_seq INT NOT NULL,
    role VARCHAR(16) NOT NULL COMMENT 'INTERVIEWER|RESPONDENT|DIRECTOR',
    content MEDIUMTEXT NOT NULL,
    sanitized_content MEDIUMTEXT NULL COMMENT '脱敏后正文（落库存脱敏域文本）',
    probe_move VARCHAR(16) NULL COMMENT 'CLOSE|ACK_AND_SWITCH|ANGLE|ADVANCE|OPEN_DRILL',
    question_id BIGINT NULL,
    blocked_reason VARCHAR(64) NULL COMMENT '出模闸拦截原因（REPEATED|PROHIBITED|TRUNCATED|...）',
    idempotency_key VARCHAR(64) NULL COMMENT '话轮幂等键',
    persist_retries INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_interview_turn_idem (session_id, idempotency_key),
    KEY idx_interview_turn_session (session_id, turn_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 受访者已讲事实累积摘要（记忆三段装配源）
CREATE TABLE IF NOT EXISTS interview_fact_summary (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    summary_text TEXT NOT NULL,
    turn_cutoff INT NOT NULL COMMENT '摘要覆盖到第几轮',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_interview_fact_summary_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 逐字稿资产（L0-L3 确定性评级）
CREATE TABLE IF NOT EXISTS interview_asset (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NULL COMMENT 'AI 访谈自动归集来源；人工上传为空',
    case_id BIGINT NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO|UPLOAD',
    title VARCHAR(255) NOT NULL,
    content LONGTEXT NOT NULL COMMENT '脱敏域 Markdown 正文',
    snapshot_hash CHAR(64) NOT NULL COMMENT '冻结快照哈希（防覆盖）',
    archive_grade VARCHAR(4) NOT NULL DEFAULT 'L0' COMMENT 'L0|L1|L2|L3',
    grade_basis JSON NULL COMMENT '六项检查逐项依据',
    turn_count INT NOT NULL DEFAULT 0,
    metadata_json JSON NULL COMMENT '13 个业务元数据字段（对齐 structuredContext）',
    word_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_interview_asset_case (case_id),
    KEY idx_interview_asset_grade (archive_grade, source),
    KEY idx_interview_asset_hash (snapshot_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 访谈报告（案例卡等）
CREATE TABLE IF NOT EXISTS interview_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    case_id BIGINT NOT NULL,
    session_id BIGINT NULL,
    asset_id BIGINT NULL,
    report_type VARCHAR(32) NOT NULL DEFAULT 'CASE_CARD' COMMENT 'P1 仅 CASE_CARD；社区/门店模板 P2',
    report_json JSON NOT NULL COMMENT '结构化结论（核心发现/时间线/策略/画像/三轴）',
    evidence_verification JSON NULL COMMENT '原声逐字核验结果与降级记录',
    missing_items JSON NULL COMMENT '缺失三分类 interview_gap|external_verification|future_research',
    score INT NULL COMMENT '质量分（缺失 1 项封顶 79）',
    replicability_level VARCHAR(4) NULL COMMENT 'L0-L3 可复制性（成功案例不得直接判 validated）',
    input_snapshot_hash CHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_interview_report_case (case_id, report_type),
    KEY idx_interview_report_snapshot (input_snapshot_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 报告持久化任务（租约模式，列设计对齐 agent_async_task 契约）
CREATE TABLE IF NOT EXISTS interview_report_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    case_id BIGINT NOT NULL,
    session_id BIGINT NULL,
    task_type VARCHAR(32) NOT NULL DEFAULT 'CASE_CARD',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|RUNNING|SUCCEEDED|RETRYABLE|BLOCKED',
    input_snapshot_hash CHAR(64) NOT NULL COMMENT '幂等：相同输入快照复用同一任务',
    lease_owner VARCHAR(64) NULL,
    lease_expires_at DATETIME NULL,
    lease_epoch INT NOT NULL DEFAULT 0 COMMENT '防旧执行复活',
    retries INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3,
    error_class VARCHAR(16) NULL COMMENT 'retryable|blocked',
    error_message TEXT NULL,
    report_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_interview_report_task_snapshot (task_type, input_snapshot_hash),
    KEY idx_interview_report_task_status (status, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 打法卡（P2 实现，本期建表）
CREATE TABLE IF NOT EXISTS playbook_card (
    id BIGINT NOT NULL AUTO_INCREMENT,
    asset_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    quote_registry JSON NULL COMMENT '原声编号表快照',
    constraint_status VARCHAR(16) NULL COMMENT 'ok|blocked',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_playbook_card_asset (asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 共性提炼（P2 实现，本期建表）
CREATE TABLE IF NOT EXISTS commonality_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    asset_ids JSON NOT NULL,
    profile_doc LONGTEXT NULL COMMENT '共性客户画像文档',
    selling_doc LONGTEXT NULL COMMENT '共性项目卖点文档',
    confidence VARCHAR(16) NULL COMMENT 'TENDENCY|MEDIUM|HIGH（按样本数固定）',
    individual_signals JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
