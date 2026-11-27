package com.jobpilot.modules.eval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.modules.eval.entity.LlmEvalRun;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LlmEvalRunMapper extends BaseMapper<LlmEvalRun> {
}
