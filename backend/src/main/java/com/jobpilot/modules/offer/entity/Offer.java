package com.jobpilot.modules.offer.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jobpilot.common.mybatis.JsonbStringTypeHandler;
import lombok.Data;

/** Offer 决策记录（F6）；offer_group 为分组，analysis 持久化最近一次分析。 */
@Data
@TableName(value = "offers", autoResultMap = true)
public class Offer {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long companyId;
    private Long jobPositionId;
    private Long applicationId;
    private Integer monthlySalary;
    private Integer baseSalary;
    private BigDecimal performanceRatio;
    private String socialInsuranceBase;
    private String socialInsuranceRatio;
    private String housingFundBase;
    private String housingFundRatio;
    private Boolean twoDayWeekend;
    private String workHours;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String benefits;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String allowances;

    private String annualBonus;
    private String quarterlyBonus;
    private String remark;
    private String offerGroup;

    /** 决策依据快照（FR6.7）：当时看重什么、是否最终选择及原因。 */
    private String decisionPriorities;
    private Boolean finalChoice;
    private String finalChoiceReason;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String analysis;

    private OffsetDateTime analyzedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
