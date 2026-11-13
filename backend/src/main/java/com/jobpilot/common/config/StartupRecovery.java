package com.jobpilot.common.config;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jobpilot.modules.chat.entity.ChatSession;
import com.jobpilot.modules.chat.mapper.ChatSessionMapper;
import com.jobpilot.modules.resume.entity.Resume;
import com.jobpilot.modules.resume.mapper.ResumeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动恢复（NFR7 可靠性）：进程异常退出会留下 PROCESSING 状态，启动时统一回收，
 * 避免会话永久不可切、简历永久不可重试。
 */
@Component
public class StartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final ChatSessionMapper sessionMapper;
    private final ResumeMapper resumeMapper;

    public StartupRecovery(ChatSessionMapper sessionMapper, ResumeMapper resumeMapper) {
        this.sessionMapper = sessionMapper;
        this.resumeMapper = resumeMapper;
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
        if (sessions > 0 || resumes > 0) {
            log.warn("启动恢复：回收中断会话 {} 个、中断简历分析 {} 个", sessions, resumes);
        }
    }
}
