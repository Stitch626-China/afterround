-- 场景级基线（1.24.0）：基线是被「设为基线」的运行，历史保留，is_current 只做标记。
CREATE TABLE llm_eval_baselines (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      NOT NULL DEFAULT 1,
    scene           VARCHAR(32) NOT NULL,
    run_id          BIGINT      NOT NULL REFERENCES llm_eval_runs (id) ON DELETE RESTRICT,
    label           VARCHAR(100),
    provider_key    VARCHAR(64) NOT NULL,
    model           VARCHAR(128) NOT NULL,
    prompt_version  VARCHAR(64) NOT NULL,
    prompt_hash     VARCHAR(64) NOT NULL,
    corpus_hash     VARCHAR(64) NOT NULL,
    engine_version  VARCHAR(32) NOT NULL,
    params          JSONB       NOT NULL,
    metrics         JSONB       NOT NULL,
    is_current      BOOLEAN     NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_llm_eval_baselines_run_scene UNIQUE (run_id, scene)
);
CREATE UNIQUE INDEX uk_llm_eval_baselines_current
    ON llm_eval_baselines (scene) WHERE is_current;
CREATE INDEX idx_llm_eval_baselines_scene_created
    ON llm_eval_baselines (scene, created_at DESC);
COMMENT ON TABLE llm_eval_baselines IS '场景级基线；历史保留，is_current 仅标记当前基线';
COMMENT ON COLUMN llm_eval_baselines.label IS '可选人读标签，如「v3 发布前」；创建基线时写入';
COMMENT ON COLUMN llm_eval_baselines.prompt_hash IS '影响面提示用；不参与可比性判定';
