-- M3-a 投递档案（F8）：一次投递 = 岗位 + 可选简历版本 + 状态；
-- 删除被投递引用的简历 / 岗位由外键 RESTRICT 与服务层 409 双重兜底；
-- interview_events 的引用校验在 M3-b 建表后接入。
CREATE TABLE applications (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL DEFAULT 1,
    job_position_id BIGINT       NOT NULL REFERENCES job_positions (id) ON DELETE RESTRICT,
    resume_id       BIGINT       REFERENCES resumes (id) ON DELETE RESTRICT,
    channel         VARCHAR(64),
    applied_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status          VARCHAR(16)  NOT NULL DEFAULT 'APPLIED',
    outcome         VARCHAR(500),
    notes           VARCHAR(2000),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_applications_status CHECK (
        status IN ('APPLIED', 'INTERVIEWING', 'OFFERED', 'REJECTED', 'WITHDRAWN')
    )
);
CREATE INDEX idx_applications_job ON applications (job_position_id);
CREATE INDEX idx_applications_resume ON applications (resume_id);
CREATE INDEX idx_applications_created ON applications (created_at DESC);
COMMENT ON TABLE applications IS '投递档案（F8）；状态向前同步岗位状态，只升不降';

ALTER TABLE offers ADD COLUMN application_id BIGINT REFERENCES applications (id) ON DELETE SET NULL;
CREATE INDEX idx_offers_application ON offers (application_id);
