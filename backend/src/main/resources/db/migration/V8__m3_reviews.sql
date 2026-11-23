-- M3-b 真实面试复盘（F4/F5）：面试事件 → 复盘会话 → 结构化分析 → 改进动作。
-- 归因链：applications → interview_events → review_sessions → review_analyses / improvement_actions。
CREATE TABLE interview_events (
    id               BIGSERIAL    PRIMARY KEY,
    user_id          BIGINT       NOT NULL DEFAULT 1,
    application_id   BIGINT       NOT NULL REFERENCES applications (id) ON DELETE RESTRICT,
    round_no         INT          NOT NULL,
    round_type       VARCHAR(16)  NOT NULL,
    interviewer_role VARCHAR(64),
    format           VARCHAR(16),
    interview_time   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    result           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    notes            VARCHAR(1000),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_interview_events_round_no CHECK (round_no >= 1),
    CONSTRAINT ck_interview_events_round_type CHECK (
        round_type IN ('HR', 'TECH', 'CROSS', 'DIRECTOR', 'FINAL', 'OTHER')
    ),
    CONSTRAINT ck_interview_events_format CHECK (
        format IS NULL OR format IN ('PHONE', 'VIDEO', 'ONSITE', 'OTHER')
    ),
    CONSTRAINT ck_interview_events_result CHECK (result IN ('PASS', 'FAIL', 'PENDING')),
    CONSTRAINT uk_interview_events_round UNIQUE (application_id, round_no)
);
CREATE INDEX idx_interview_events_application ON interview_events (application_id);
CREATE INDEX idx_interview_events_time ON interview_events (interview_time DESC);
COMMENT ON TABLE interview_events IS '真实面试轮次（FR5.1）；同一投递同一轮次唯一';

CREATE TABLE review_sessions (
    id                    BIGSERIAL    PRIMARY KEY,
    user_id               BIGINT       NOT NULL DEFAULT 1,
    event_id              BIGINT       NOT NULL REFERENCES interview_events (id) ON DELETE RESTRICT,
    input_type            VARCHAR(16)  NOT NULL,
    audio_storage_key     VARCHAR(255),
    audio_file_name       VARCHAR(255),
    audio_size            BIGINT,
    audio_duration_seconds INT,
    transcript            TEXT,
    transcript_segments   JSONB,
    notes                 TEXT,
    asr_status            VARCHAR(16)  NOT NULL DEFAULT 'SKIPPED',
    asr_error             VARCHAR(500),
    asr_retry_count       INT          NOT NULL DEFAULT 0,
    analyze_status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    analyze_error         VARCHAR(500),
    analyze_retry_count   INT          NOT NULL DEFAULT 0,
    status                VARCHAR(16)  NOT NULL DEFAULT 'UPLOADED',
    idempotency_key       VARCHAR(64),
    confirmed             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_review_sessions_input_type CHECK (input_type IN ('AUDIO', 'TEXT', 'NOTES')),
    CONSTRAINT ck_review_sessions_asr_status CHECK (
        asr_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'SKIPPED')
    ),
    CONSTRAINT ck_review_sessions_analyze_status CHECK (
        analyze_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'SKIPPED')
    ),
    CONSTRAINT ck_review_sessions_status CHECK (
        status IN ('UPLOADED', 'TRANSCRIBING', 'ANALYZING', 'READY', 'FAILED')
    ),
    CONSTRAINT ck_review_sessions_retry CHECK (asr_retry_count >= 0 AND analyze_retry_count >= 0),
    CONSTRAINT uk_review_sessions_event UNIQUE (event_id)
);
CREATE UNIQUE INDEX uk_review_sessions_idempotency ON review_sessions (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_review_sessions_status ON review_sessions (status);
CREATE INDEX idx_review_sessions_created ON review_sessions (created_at DESC);
COMMENT ON TABLE review_sessions IS '真实面试复盘会话（FR4）；一次面试事件只允许一条复盘';

CREATE TABLE review_analyses (
    id                BIGSERIAL    PRIMARY KEY,
    review_session_id BIGINT       NOT NULL UNIQUE REFERENCES review_sessions (id) ON DELETE CASCADE,
    analysis          JSONB        NOT NULL,
    prompt_version    VARCHAR(32)  NOT NULL,
    rubric_version    VARCHAR(32)  NOT NULL,
    taxonomy_version  VARCHAR(32)  NOT NULL,
    llm_provider      VARCHAR(64),
    llm_model         VARCHAR(128),
    degraded          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE review_analyses IS '复盘结构化分析（FR4.3/FR4.7）；重跑覆盖，保留版本与模型';

CREATE TABLE improvement_actions (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           BIGINT       NOT NULL DEFAULT 1,
    application_id    BIGINT       REFERENCES applications (id) ON DELETE SET NULL,
    source_review_id  BIGINT       REFERENCES review_sessions (id) ON DELETE CASCADE,
    source_event_id   BIGINT       REFERENCES interview_events (id) ON DELETE SET NULL,
    action            VARCHAR(500) NOT NULL,
    category          VARCHAR(64),
    status            VARCHAR(16)  NOT NULL DEFAULT 'TODO',
    due_at            TIMESTAMPTZ,
    verified_event_id BIGINT       REFERENCES interview_events (id) ON DELETE SET NULL,
    evidence          VARCHAR(2000),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_improvement_actions_status CHECK (
        status IN ('TODO', 'IN_PROGRESS', 'VERIFIED', 'DROPPED')
    )
);
CREATE INDEX idx_improvement_actions_status ON improvement_actions (status);
CREATE INDEX idx_improvement_actions_review ON improvement_actions (source_review_id);
CREATE INDEX idx_improvement_actions_application ON improvement_actions (application_id);
COMMENT ON TABLE improvement_actions IS '改进动作（FR4.4/FR5.6）；来自复盘，可在后续面试验证';
