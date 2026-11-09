-- ============================================================
-- 职点（JobPilot）M1 初始表结构
-- 约定：
--   1. 所有表预留 user_id，单用户阶段固定为 1（后端统一注入，前端不传）
--   2. 异步任务统一状态四件套：status + CHECK + error + retry_count
--   3. 删除一律硬删除，由业务层级联清理
-- ============================================================

-- ---------- 模型配置（X1，数据库驱动热更新） ----------

CREATE TABLE llm_providers (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL DEFAULT 1,
    provider_key   VARCHAR(64)  NOT NULL,
    label          VARCHAR(64)  NOT NULL,
    base_url       VARCHAR(512) NOT NULL,
    api_key_cipher TEXT         NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order     INT          NOT NULL DEFAULT 10,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_llm_providers_key UNIQUE (provider_key)
);
COMMENT ON TABLE  llm_providers IS '模型服务商：Provider = baseUrl + apiKey（不含 model）';
COMMENT ON COLUMN llm_providers.api_key_cipher IS 'AES-GCM 密文，对外一律掩码，永不返回明文';

CREATE TABLE llm_models (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL DEFAULT 1,
    provider_id BIGINT       NOT NULL REFERENCES llm_providers (id) ON DELETE CASCADE,
    model_name  VARCHAR(128) NOT NULL,
    label       VARCHAR(64)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order  INT          NOT NULL DEFAULT 10,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_llm_models_provider_model UNIQUE (provider_id, model_name)
);
CREATE INDEX idx_llm_models_provider ON llm_models (provider_id);

CREATE TABLE llm_global_setting (
    id                   SMALLINT    PRIMARY KEY DEFAULT 1,
    user_id              BIGINT      NOT NULL DEFAULT 1,
    default_provider_key VARCHAR(64),
    default_model        VARCHAR(128),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_llm_global_setting_single_row CHECK (id = 1)
);
COMMENT ON TABLE llm_global_setting IS '全局默认 Provider + Model，单行表';

-- 调用埋点与成本统计（NFR2 / NFR6）
CREATE TABLE llm_call_logs (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           BIGINT       NOT NULL DEFAULT 1,
    trace_id          VARCHAR(64),
    scene             VARCHAR(32)  NOT NULL,
    provider_key      VARCHAR(64),
    model             VARCHAR(128),
    prompt_tokens     INT,
    completion_tokens INT,
    total_tokens      INT,
    duration_ms       BIGINT,
    success           BOOLEAN      NOT NULL,
    error             VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_llm_call_logs_created ON llm_call_logs (created_at DESC);
CREATE INDEX idx_llm_call_logs_trace ON llm_call_logs (trace_id);

-- ---------- 对话会话（M1 能力验证载体） ----------

CREATE TABLE chat_sessions (
    id                   BIGSERIAL   PRIMARY KEY,
    user_id              BIGINT      NOT NULL DEFAULT 1,
    title                VARCHAR(128),
    current_provider_key VARCHAR(64),
    current_model        VARCHAR(128),
    status               VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_chat_sessions_status CHECK (status IN ('IDLE', 'PROCESSING', 'LOCKED'))
);
CREATE INDEX idx_chat_sessions_created ON chat_sessions (created_at DESC);

CREATE TABLE chat_messages (
    id           BIGSERIAL   PRIMARY KEY,
    user_id      BIGINT      NOT NULL DEFAULT 1,
    session_id   BIGINT      NOT NULL REFERENCES chat_sessions (id) ON DELETE CASCADE,
    role         VARCHAR(16) NOT NULL,
    content      TEXT        NOT NULL,
    llm_provider VARCHAR(64),
    llm_model    VARCHAR(128),
    trace_id     VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_chat_messages_role CHECK (role IN ('USER', 'ASSISTANT'))
);
CREATE INDEX idx_chat_messages_session ON chat_messages (session_id, id);

-- ---------- 简历中心（F1 轨一） ----------

CREATE TABLE resumes (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL DEFAULT 1,
    file_hash      VARCHAR(64)  NOT NULL,
    file_name      VARCHAR(255),
    file_type      VARCHAR(16),
    file_size      BIGINT,
    storage_key    VARCHAR(512),
    resume_text    TEXT,
    analyze_status VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    analyze_error  VARCHAR(500),
    retry_count    INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_resumes_file_hash UNIQUE (file_hash),
    CONSTRAINT ck_resumes_analyze_status CHECK (analyze_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);
CREATE INDEX idx_resumes_created ON resumes (created_at DESC);

CREATE TABLE resume_analyses (
    id                             BIGSERIAL   PRIMARY KEY,
    user_id                        BIGINT      NOT NULL DEFAULT 1,
    resume_id                      BIGINT      NOT NULL REFERENCES resumes (id) ON DELETE CASCADE,
    overall_score                  INT,
    score_technical_depth          INT,
    score_project_quality          INT,
    score_engineering_ability      INT,
    score_expression_structure     INT,
    score_job_match_potential      INT,
    basic_info                     JSONB,
    strengths                      JSONB,
    weaknesses                     JSONB,
    suggestions                    JSONB,
    career_directions              JSONB,
    llm_provider                   VARCHAR(64),
    llm_model                      VARCHAR(128),
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_resume_analyses_resume UNIQUE (resume_id)
);
COMMENT ON TABLE resume_analyses IS '简历分析结果，与 resumes 一对一同生共死';
