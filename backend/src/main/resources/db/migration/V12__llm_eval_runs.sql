-- 评测运行器（1.23.0）：异步运行 + 逐用例结果；同一时间只允许一个活跃运行。
CREATE TABLE llm_eval_runs (
    id                 BIGSERIAL   PRIMARY KEY,
    user_id            BIGINT      NOT NULL DEFAULT 1,
    status             VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    partial            BOOLEAN     NOT NULL DEFAULT false,
    scenes             JSONB       NOT NULL,
    model_snapshot     JSONB       NOT NULL,
    prompt_snapshot    JSONB       NOT NULL,
    corpus_hash        VARCHAR(64) NOT NULL,
    engine_version     VARCHAR(32) NOT NULL,
    params             JSONB       NOT NULL,
    total_cases        INT         NOT NULL DEFAULT 0,
    completed_cases    INT         NOT NULL DEFAULT 0,
    error_cases        INT         NOT NULL DEFAULT 0,
    error              VARCHAR(500),
    retry_count        INT         NOT NULL DEFAULT 0,
    cancel_requested   BOOLEAN     NOT NULL DEFAULT false,
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_llm_eval_runs_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_llm_eval_runs_counts CHECK (
        total_cases >= 0 AND completed_cases >= 0 AND error_cases >= 0)
);
CREATE INDEX idx_llm_eval_runs_created ON llm_eval_runs (created_at DESC);
CREATE INDEX idx_llm_eval_runs_status ON llm_eval_runs (status);
-- 单实例服务锁之外的 DB 兜底：同一时间只允许一个活跃运行。
CREATE UNIQUE INDEX uk_llm_eval_runs_active
    ON llm_eval_runs ((1)) WHERE status IN ('PENDING', 'PROCESSING');
COMMENT ON COLUMN llm_eval_runs.partial IS 'true 表示只重跑部分用例（ERROR 重试），不可作为基线、不参与完整对比';
COMMENT ON COLUMN llm_eval_runs.error_cases IS '执行错误（ERROR）用例数，不是质量失败数';

CREATE TABLE llm_eval_run_cases (
    id          BIGSERIAL   PRIMARY KEY,
    run_id      BIGINT      NOT NULL REFERENCES llm_eval_runs (id) ON DELETE CASCADE,
    scene       VARCHAR(32) NOT NULL,
    case_id     VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    l1_passed   BOOLEAN,
    l2_passed   BOOLEAN,
    l3_passed   BOOLEAN,
    results     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    scores      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    topics      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    loss_tags   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    tokens      INT,
    elapsed_ms  BIGINT,
    error       VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_llm_eval_run_cases UNIQUE (run_id, scene, case_id),
    CONSTRAINT ck_llm_eval_run_cases_status CHECK (
        status IN ('PENDING', 'PASS', 'FAIL', 'GRACEFUL', 'ERROR', 'SKIPPED'))
);
CREATE INDEX idx_llm_eval_run_cases_run ON llm_eval_run_cases (run_id);
COMMENT ON TABLE llm_eval_run_cases IS '逐用例结果；scores/topics/loss_tags 供基线对比，不依赖报告文件';
