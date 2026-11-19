package com.jobpilot.common.config;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jobpilot.modules.chat.entity.ChatSession;
import com.jobpilot.modules.chat.mapper.ChatSessionMapper;
import com.jobpilot.modules.interview.entity.InterviewSession;
import com.jobpilot.modules.interview.mapper.InterviewSessionMapper;
import com.jobpilot.modules.job.entity.JobMatchResult;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobMatchResultMapper;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import com.jobpilot.modules.resume.entity.Resume;
import com.jobpilot.modules.resume.mapper.ResumeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动恢复（NFR7 可靠性）：进程异常退出会留下 PROCESSING 状态，启动时统一回收，
 * 避免会话永久不可切、简历/岗位解析与匹配永久不可重试。
 */
@Component
public class StartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final ChatSessionMapper sessionMapper;
    private final ResumeMapper resumeMapper;
    private final JobPositionMapper jobMapper;
    private final JobMatchResultMapper matchMapper;
    private final InterviewSessionMapper interviewMapper;

    public StartupRecovery(ChatSessionMapper sessionMapper, ResumeMapper resumeMapper,
                           JobPositionMapper jobMapper, JobMatchResultMapper matchMapper,
                           InterviewSessionMapper interviewMapper) {
        this.sessionMapper = sessionMapper;
        this.resumeMapper = resumeMapper;
        this.jobMapper = jobMapper;
        this.matchMapper = matchMapper;
        this.interviewMapper = interviewMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        int sessions = sessionMapper.update(null, Wrappers.<ChatSession>lambdaUpdate()
                .eq(ChatSession::getStatus, "PROCESSING")
                .set(ChatSession::getStatus, "IDLE")
                .set(ChatSession::getUpdatedAt, OffsetDateTime.now()));
        int resumes = resumeMapper.update(null, Wrappers.<Resume>lambdaUpdate()
                .eq(Resume::getAnalyzeStatus, "PROCESSING")
                .set(Resume::getAnalyzeStatus, "PENDING")
                .set(Resume::getAnalyzeError, "上次分析因服务重启中断，请重新触发")
                .set(Resume::getUpdatedAt, OffsetDateTime.now()));
        int jobs = jobMapper.update(null, Wrappers.<JobPosition>lambdaUpdate()
                .eq(JobPosition::getParseStatus, "PROCESSING")
                .set(JobPosition::getParseStatus, "PENDING")
                .set(JobPosition::getParseError, "上次解析因服务重启中断，请重新触发")
                .set(JobPosition::getUpdatedAt, OffsetDateTime.now()));
        int matches = matchMapper.update(null, Wrappers.<JobMatchResult>lambdaUpdate()
                .eq(JobMatchResult::getStatus, "PROCESSING")
                .set(JobMatchResult::getStatus, "PENDING")
                .set(JobMatchResult::getError, "上次匹配因服务重启中断，请重新发起")
                .set(JobMatchResult::getUpdatedAt, OffsetDateTime.now()));
        int interviews = interviewMapper.update(null, Wrappers.<InterviewSession>lambdaUpdate()
                .in(InterviewSession::getStatus, "PREPARING", "GENERATING")
                .set(InterviewSession::getStatus, "FAILED")
                .set(InterviewSession::getError, "上次出题因服务重启中断，请重试")
                .set(InterviewSession::getUpdatedAt, OffsetDateTime.now()));
        int evaluations = interviewMapper.update(null, Wrappers.<InterviewSession>lambdaUpdate()
                .eq(InterviewSession::getEvaluateStatus, "PROCESSING")
                .set(InterviewSession::getEvaluateStatus, "FAILED")
                .set(InterviewSession::getEvaluateError, "上次评估因服务重启中断，请重新交卷")
                .set(InterviewSession::getUpdatedAt, OffsetDateTime.now()));
        if (sessions + resumes + jobs + matches + interviews + evaluations > 0) {
            log.warn("启动恢复：回收中断会话 {} 个、简历分析 {} 个、JD 解析 {} 个、匹配 {} 个、面试出题 {} 个、面试评估 {} 个",
                    sessions, resumes, jobs, matches, interviews, evaluations);
        }
    }
}
