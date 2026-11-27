-- 提示词工作台（1.22.0）：system + user 版本；内置默认在 classpath，DB 仅存覆盖版本。
CREATE TABLE llm_prompt_versions (
    id             BIGSERIAL   PRIMARY KEY,
    user_id        BIGINT      NOT NULL DEFAULT 1,
    prompt_key     VARCHAR(64) NOT NULL,
    version_no     INT         NOT NULL,
    system_content TEXT        NOT NULL,
    user_content   TEXT        NOT NULL,
    content_hash   VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    note           VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    CONSTRAINT uk_llm_prompt_versions_key_no UNIQUE (prompt_key, version_no),
    CONSTRAINT ck_llm_prompt_versions_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_llm_prompt_versions_version_no CHECK (version_no > 0)
);
-- 每个 key 最多一个草稿、一个生效版本；并发发布由唯一索引兜底。
CREATE UNIQUE INDEX uk_llm_prompt_versions_active
    ON llm_prompt_versions (prompt_key) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uk_llm_prompt_versions_draft
    ON llm_prompt_versions (prompt_key) WHERE status = 'DRAFT';
CREATE INDEX idx_llm_prompt_versions_key_created
    ON llm_prompt_versions (prompt_key, created_at DESC);
COMMENT ON TABLE llm_prompt_versions IS '提示词 system+user 版本；内置默认在 classpath，DB 仅存覆盖版本';
COMMENT ON COLUMN llm_prompt_versions.content_hash IS 'SHA-256(system + 分隔线 + user)，用于影响面提示';
