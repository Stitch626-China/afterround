-- FR4.2 复盘快速录入：无 JD 时允许创建来源为 MANUAL 的岗位，手动岗位不参与行情统计
ALTER TABLE job_positions DROP CONSTRAINT ck_job_positions_source_type;
ALTER TABLE job_positions ADD CONSTRAINT ck_job_positions_source_type
    CHECK (source_type IN ('TEXT', 'FILE', 'IMAGE', 'MANUAL'));
