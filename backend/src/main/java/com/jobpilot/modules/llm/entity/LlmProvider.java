package com.jobpilot.modules.llm.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Provider = baseUrl + apiKey（不含 model），一个 Provider 可挂多个 Model。 */
@Data
@TableName("llm_providers")
public class LlmProvider {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String providerKey;
    private String label;
    private String baseUrl;
    /** AES-GCM 密文，永不对外返回。 */
    private String apiKeyCipher;
    private Boolean enabled;
    private Integer sortOrder;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
