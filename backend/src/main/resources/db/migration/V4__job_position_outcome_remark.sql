-- 岗位库：把「已关闭」拆成「录用 / 淘汰」两种终态，并支持用户备注（面试轮次、淘汰环节等）

ALTER TABLE job_positions ADD COLUMN remark VARCHAR(200);
COMMENT ON COLUMN job_positions.remark IS '用户备注：如一面 / 二面、简历淘汰 / 面试淘汰';

-- 旧数据只有笼统的 CLOSED，按语义最接近的「淘汰」迁移；用户可在页面上改为「录用」
UPDATE job_positions SET status = 'REJECTED' WHERE status = 'CLOSED';

ALTER TABLE job_positions DROP CONSTRAINT ck_job_positions_status;
ALTER TABLE job_positions ADD CONSTRAINT ck_job_positions_status
    CHECK (status IN ('SAVED', 'APPLIED', 'INTERVIEWING', 'OFFERED', 'REJECTED'));
