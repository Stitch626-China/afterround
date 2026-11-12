-- 用户填写的薪资（优先于 LLM 从简历提取的值）
ALTER TABLE resumes ADD COLUMN current_salary INT;
ALTER TABLE resumes ADD COLUMN expected_salary INT;

COMMENT ON COLUMN resumes.current_salary IS '当前月薪（元/月），用户填写值，优先于分析结果中的提取值';
COMMENT ON COLUMN resumes.expected_salary IS '期望月薪（元/月），用户填写值';
