-- ============================================================
-- M2 第一批：场景级模型绑定、公司、岗位库、匹配结果
-- 约定同 V1：user_id 固定 1；异步任务状态四件套；硬删除 + 业务级联
-- ============================================================

-- ---------- 场景级模型绑定（FRX.11） ----------

CREATE TABLE llm_scene_settings (
    id           BIGSERIAL   PRIMARY KEY,
    user_id      BIGINT      NOT NULL DEFAULT 1,
    scene        VARCHAR(32) NOT NULL,
    provider_key VARCHAR(64) NOT NULL,
    model        VARCHAR(128) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_llm_scene_settings_scene UNIQUE (scene),
    CONSTRAINT ck_llm_scene_settings_scene CHECK (scene IN (
        'RESUME_ANALYZE', 'CHAT', 'JOB_MATCH', 'INTERVIEW_QUESTION',
        'INTERVIEW_EVALUATE', 'OFFER_ANALYZE', 'REVIEW_ANALYZE'
    ))
);
COMMENT ON TABLE llm_scene_settings IS '场景级 Provider + Model 绑定；未配置回落全局默认';
COMMENT ON COLUMN llm_scene_settings.scene IS 'LlmScene 枚举，契约一次性定义 7 个场景';

-- ---------- 公司（F5 / F6 数据前置） ----------

CREATE TABLE companies (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL DEFAULT 1,
    name       VARCHAR(128) NOT NULL,
    intro      TEXT,
    tags       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_companies_name UNIQUE (name)
);
COMMENT ON TABLE companies IS '公司最小 CRUD；不做模糊自动合并，删除被岗位引用时拒绝';

-- ---------- 岗位库（F2） ----------

CREATE TABLE job_positions (
    id                 BIGSERIAL    PRIMARY KEY,
    user_id            BIGINT       NOT NULL DEFAULT 1,
    company_id         BIGINT       REFERENCES companies (id) ON DELETE RESTRICT,
    title              VARCHAR(128),
    city               VARCHAR(64),
    salary_min         INT,
    salary_max         INT,
    experience         VARCHAR(64),
    education          VARCHAR(64),
    source_type        VARCHAR(8)   NOT NULL,
    file_name          VARCHAR(255),
    storage_key        VARCHAR(512),
    raw_text           TEXT         NOT NULL,
    must_have_skills   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    nice_to_have_skills JSONB       NOT NULL DEFAULT '[]'::jsonb,
    responsibility     TEXT,
    status             VARCHAR(16)  NOT NULL DEFAULT 'SAVED',
    parse_status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    parse_error        VARCHAR(500),
    retry_count        INT          NOT NULL DEFAULT 0,
    llm_provider       VARCHAR(64),
    llm_model          VARCHAR(128),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_job_positions_source_type CHECK (source_type IN ('TEXT', 'FILE')),
    CONSTRAINT ck_job_positions_status CHECK (status IN ('SAVED', 'APPLIED', 'INTERVIEWING', 'CLOSED')),
    CONSTRAINT ck_job_positions_parse_status CHECK (parse_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_job_positions_salary CHECK (
        (salary_min IS NULL OR salary_min >= 0) AND (salary_max IS NULL OR salary_max >= 0)
    )
);
CREATE INDEX idx_job_positions_created ON job_positions (created_at DESC);
CREATE INDEX idx_job_positions_company ON job_positions (company_id);
CREATE INDEX idx_job_positions_parse_status ON job_positions (parse_status);
COMMENT ON TABLE job_positions IS 'JD 原文 + LLM 结构化解析结果；parse_status 为异步解析状态机';

-- ---------- 简历-JD 匹配（F2 FR2.3） ----------

CREATE TABLE job_match_results (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      NOT NULL DEFAULT 1,
    resume_id       BIGINT      NOT NULL REFERENCES resumes (id) ON DELETE CASCADE,
    job_position_id BIGINT      NOT NULL REFERENCES job_positions (id) ON DELETE CASCADE,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    error           VARCHAR(500),
    retry_count     INT         NOT NULL DEFAULT 0,
    overall_score   INT,
    detail          JSONB,
    llm_provider    VARCHAR(64),
    llm_model       VARCHAR(128),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_job_match_results_pair UNIQUE (resume_id, job_position_id),
    CONSTRAINT ck_job_match_results_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);
CREATE INDEX idx_job_match_results_created ON job_match_results (created_at DESC);
CREATE INDEX idx_job_match_results_resume ON job_match_results (resume_id);
COMMENT ON TABLE job_match_results IS '同一 (resume_id, job_position_id) 唯一；重复发起表示重跑';

-- ---------- F7 简历对比：记录分析使用的 Prompt 版本 ----------

ALTER TABLE resume_analyses ADD COLUMN prompt_version VARCHAR(64);
COMMENT ON COLUMN resume_analyses.prompt_version IS '简历分析使用的 Prompt 版本；F7 对比用，M1 历史数据为 null';
