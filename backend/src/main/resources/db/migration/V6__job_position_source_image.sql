-- FR2.8 截图导入：岗位来源新增 IMAGE（原图不落库，仅保存视觉识别文本）
ALTER TABLE job_positions DROP CONSTRAINT ck_job_positions_source_type;
ALTER TABLE job_positions ADD CONSTRAINT ck_job_positions_source_type
    CHECK (source_type IN ('TEXT', 'FILE', 'IMAGE'));
