package com.jobpilot.modules.prompt.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 提示词 system+user 版本；内置默认在 classpath，DB 只存覆盖版本。 */
@Data
@TableName("llm_prompt_versions")
public class LlmPromptVersion {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String promptKey;
    private Integer versionNo;
    private String systemContent;
    private String userContent;
    private String contentHash;
    /** DRAFT / ACTIVE / ARCHIVED */
    private String status;
    /** 可清空：保存草稿时传空也要真正清掉备注。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String note;
    private OffsetDateTime createdAt;
    private OffsetDateTime publishedAt;
}
