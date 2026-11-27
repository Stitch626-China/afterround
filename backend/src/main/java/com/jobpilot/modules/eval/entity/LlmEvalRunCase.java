package com.jobpilot.modules.eval.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jobpilot.common.mybatis.JsonbStringTypeHandler;
import lombok.Data;

/** 逐用例结果；FAIL 为质量失败，ERROR 为执行错误。 */
@Data
@TableName(value = "llm_eval_run_cases", autoResultMap = true)
public class LlmEvalRunCase {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private String scene;
    private String caseId;
    private String status;
    private Boolean l1Passed;
    private Boolean l2Passed;
    private Boolean l3Passed;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String results;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String scores;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String topics;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String lossTags;
    private Integer tokens;
    private Long elapsedMs;
    private String error;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
