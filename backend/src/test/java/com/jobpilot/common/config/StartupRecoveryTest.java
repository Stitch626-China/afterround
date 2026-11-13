package com.jobpilot.common.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.modules.chat.entity.ChatSession;
import com.jobpilot.modules.chat.mapper.ChatSessionMapper;
import com.jobpilot.modules.resume.entity.Resume;
import com.jobpilot.modules.resume.mapper.ResumeMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StartupRecoveryTest {

    @Mock
    private ChatSessionMapper sessionMapper;

    @Mock
    private ResumeMapper resumeMapper;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(ChatSession.class, Resume.class);
    }

    @Test
    void recoversInterruptedSessionsAndResumes() throws Exception {
        when(sessionMapper.update(isNull(), any())).thenReturn(2);
        when(resumeMapper.update(isNull(), any())).thenReturn(1);

        new StartupRecovery(sessionMapper, resumeMapper).run(null);

        verify(sessionMapper).update(isNull(), any());
        verify(resumeMapper).update(isNull(), any());
    }
}
