package com.jobpilot.modules.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.modules.eval.entity.LlmEvalRunCase;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LlmEvalRunCaseMapper extends BaseMapper<LlmEvalRunCase> {
}
