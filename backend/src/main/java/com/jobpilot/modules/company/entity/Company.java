package com.jobpilot.modules.company.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jobpilot.common.mybatis.JsonbStringTypeHandler;
import lombok.Data;

/** 公司最小 CRUD；name 全局唯一，不做模糊自动合并。 */
@Data
@TableName(value = "companies", autoResultMap = true)
public class Company {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String intro;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String tags;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
