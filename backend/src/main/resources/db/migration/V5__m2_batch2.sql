-- M2 第二批：模拟面试（F3）与 Offer 决策（F6）

-- ---------- 模拟面试会话（F3） ----------

CREATE TABLE interview_sessions (
    id                    BIGSERIAL    PRIMARY KEY,
    user_id               BIGINT       NOT NULL DEFAULT 1,
    direction             VARCHAR(64),
    direction_label       VARCHAR(128),
    resume_id             BIGINT       REFERENCES resumes (id) ON DELETE SET NULL,
    job_position_id       BIGINT       REFERENCES job_positions (id) ON DELETE SET NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'PREPARING',
    question_count        INT          NOT NULL DEFAULT 8,
    follow_up_count       INT          NOT NULL DEFAULT 1,
    answered_count        INT          NOT NULL DEFAULT 0,
    current_provider_key  VARCHAR(64),
    current_model         VARCHAR(128),
    error                 VARCHAR(500),
    retry_count           INT          NOT NULL DEFAULT 0,
    evaluate_status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    evaluate_error        VARCHAR(500),
    evaluate_retry_count  INT          NOT NULL DEFAULT 0,
    evaluate_overall_score INT,
    evaluate_categories   JSONB,
    evaluate_strengths    JSONB,
    evaluate_improvements JSONB,
    evaluate_summary      TEXT,
    evaluate_provider     VARCHAR(64),
    evaluate_model        VARCHAR(128),
    evaluated_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_interview_sessions_status CHECK (
        status IN ('PREPARING', 'IN_PROGRESS', 'GENERATING', 'SUBMITTED', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT ck_interview_sessions_evaluate_status CHECK (
        evaluate_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT ck_interview_sessions_counts CHECK (
        question_count BETWEEN 5 AND 15 AND follow_up_count BETWEEN 0 AND 3 AND answered_count >= 0
    )
);
CREATE INDEX idx_interview_sessions_created ON interview_sessions (created_at DESC);
CREATE INDEX idx_interview_sessions_direction ON interview_sessions (direction);
CREATE INDEX idx_interview_sessions_status ON interview_sessions (status);
COMMENT ON TABLE interview_sessions IS '模拟面试会话；status 为出题/作答状态机，evaluate_status 为交卷后的评估状态机';

-- ---------- 面试题目（F3，主问题 + 追问树） ----------

CREATE TABLE interview_questions (
    id               BIGSERIAL   PRIMARY KEY,
    session_id       BIGINT      NOT NULL REFERENCES interview_sessions (id) ON DELETE CASCADE,
    question_index   INT         NOT NULL,
    parent_index     INT,
    type             VARCHAR(16) NOT NULL DEFAULT 'MAIN',
    content          TEXT        NOT NULL,
    topic_summary    VARCHAR(255),
    difficulty       VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    answer_status    VARCHAR(16) NOT NULL DEFAULT 'UNANSWERED',
    answer           TEXT,
    score            INT,
    feedback         TEXT,
    reference_answer TEXT,
    key_points       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    llm_provider     VARCHAR(64),
    llm_model        VARCHAR(128),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_interview_questions_index UNIQUE (session_id, question_index),
    CONSTRAINT ck_interview_questions_type CHECK (type IN ('MAIN', 'FOLLOW_UP')),
    CONSTRAINT ck_interview_questions_difficulty CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT ck_interview_questions_answer_status CHECK (answer_status IN ('UNANSWERED', 'ANSWERED', 'SKIPPED')),
    CONSTRAINT ck_interview_questions_parent CHECK (
        (type = 'MAIN' AND parent_index IS NULL) OR (type = 'FOLLOW_UP' AND parent_index IS NOT NULL)
    ),
    CONSTRAINT ck_interview_questions_score CHECK (score IS NULL OR (score BETWEEN 0 AND 100))
);
COMMENT ON TABLE interview_questions IS '题目按 question_index 升序呈现；追问通过 parent_index 指向主问题';

-- ---------- Offer 决策（F6） ----------

CREATE TABLE offers (
    id                    BIGSERIAL    PRIMARY KEY,
    user_id               BIGINT       NOT NULL DEFAULT 1,
    company_id            BIGINT       REFERENCES companies (id) ON DELETE SET NULL,
    job_position_id       BIGINT       REFERENCES job_positions (id) ON DELETE SET NULL,
    monthly_salary        INT,
    base_salary           INT,
    performance_ratio     NUMERIC(5, 2),
    social_insurance_base VARCHAR(64),
    social_insurance_ratio VARCHAR(32),
    housing_fund_base     VARCHAR(64),
    housing_fund_ratio    VARCHAR(32),
    two_day_weekend       BOOLEAN,
    work_hours            VARCHAR(64),
    benefits              JSONB        NOT NULL DEFAULT '[]'::jsonb,
    allowances            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    annual_bonus          VARCHAR(64),
    quarterly_bonus       VARCHAR(64),
    remark                VARCHAR(500),
    offer_group           VARCHAR(8)   NOT NULL DEFAULT 'KEEP',
    analysis              JSONB,
    analyzed_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_offers_group CHECK (offer_group IN ('KEEP', 'DROP')),
    CONSTRAINT ck_offers_salary CHECK (
        (monthly_salary IS NULL OR monthly_salary >= 0) AND (base_salary IS NULL OR base_salary >= 0)
    ),
    CONSTRAINT ck_offers_performance CHECK (
        performance_ratio IS NULL OR (performance_ratio BETWEEN 0 AND 100)
    )
);
CREATE INDEX idx_offers_created ON offers (created_at DESC);
CREATE INDEX idx_offers_group ON offers (offer_group);
COMMENT ON TABLE offers IS 'Offer 决策；保留组上限 4 个由服务层校验，分析结果持久化最近一次';
