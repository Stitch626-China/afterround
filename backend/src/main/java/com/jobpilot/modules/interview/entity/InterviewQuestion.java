package com.jobpilot.modules.interview.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jobpilot.common.mybatis.JsonbStringTypeHandler;
import lombok.Data;

/** 面试题（主问题 / 追问）；question_index 为会话内呈现顺序。 */
@Data
@TableName(value = "interview_questions", autoResultMap = true)
public class InterviewQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Integer questionIndex;
    private Integer parentIndex;
    private String type;
    private String content;
    private String topicSummary;
    private String difficulty;
    private String answerStatus;
    private String answer;
    private Integer score;
    private String feedback;
    private String referenceAnswer;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String keyPoints;

    private String llmProvider;
    private String llmModel;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
