package com.jobpilot.modules.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.eval.service.EvalImpactAnalyzer;
import com.jobpilot.modules.prompt.catalog.PromptCatalog;
import com.jobpilot.modules.prompt.entity.LlmPromptVersion;
import com.jobpilot.modules.prompt.mapper.LlmPromptVersionMapper;
import com.jobpilot.modules.prompt.service.PromptRegistry;
import com.jobpilot.modules.prompt.service.PromptService;
import com.jobpilot.modules.prompt.service.PromptValidator;
import org.junit.jupiter.api.Test;

/** 服务层护栏：没有草稿、草稿无效、激活草稿、未知 key 均不得落库。 */
class PromptServiceTest {

    private final LlmPromptVersionMapper mapper = mock(LlmPromptVersionMapper.class);
    private final PromptService service = new PromptService(new PromptCatalog(), mapper,
            new PromptValidator(), PromptRegistry.builtinOnly(), mock(EvalImpactAnalyzer.class));

    @Test
    void publishWithoutDraftRejects() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.publish("resume-analysis", null))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(mapper, never()).updateById(any(LlmPromptVersion.class));
    }

    @Test
    void invalidDraftCannotBePublished() {
        when(mapper.selectOne(any())).thenReturn(draft("system 缺少占位符", "{{resumeText}}"));

        assertThatThrownBy(() -> service.publish("resume-analysis", null))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(mapper, never()).updateById(any(LlmPromptVersion.class));
    }

    @Test
    void activateDraftRejected() {
        when(mapper.selectOne(any())).thenReturn(draft("system", "user"));

        assertThatThrownBy(() -> service.activate("resume-analysis", 1))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(mapper, never()).updateById(any(LlmPromptVersion.class));
    }

    @Test
    void unknownPromptIsNotFound() {
        assertThatThrownBy(() -> service.detail("not-exist"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void versionNumberMustBePositive() {
        assertThatThrownBy(() -> service.version("resume-analysis", 0))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    private static LlmPromptVersion draft(String system, String user) {
        LlmPromptVersion row = new LlmPromptVersion();
        row.setPromptKey("resume-analysis");
        row.setVersionNo(1);
        row.setSystemContent(system);
        row.setUserContent(user);
        row.setContentHash("hash");
        row.setStatus("DRAFT");
        return row;
    }
}
