package com.jobpilot.modules.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.modules.prompt.catalog.PromptCatalog;
import com.jobpilot.modules.prompt.catalog.PromptVersionSource;
import com.jobpilot.modules.prompt.entity.LlmPromptVersion;
import com.jobpilot.modules.prompt.mapper.LlmPromptVersionMapper;
import com.jobpilot.modules.prompt.service.PromptRegistry;
import com.jobpilot.modules.prompt.service.PromptTemplate;
import org.junit.jupiter.api.Test;

class PromptRegistryTest {

    @Test
    void dbActiveOverridesBuiltin() {
        LlmPromptVersionMapper mapper = mock(LlmPromptVersionMapper.class);
        when(mapper.selectOne(any())).thenReturn(row("db system", "db user", 1, "hash-db"));

        PromptRegistry registry = new PromptRegistry(new PromptCatalog(), mapper);
        PromptTemplate template = registry.template("resume-analysis");

        assertThat(template.source()).isEqualTo(PromptVersionSource.DB);
        assertThat(template.system()).isEqualTo("db system");
        assertThat(template.versionLabel()).isEqualTo("resume-analysis-db1");
    }

    @Test
    void builtinOnlyDoesNotQueryDb() {
        PromptTemplate template = PromptRegistry.builtinOnly().template("resume-analysis");

        assertThat(template.source()).isEqualTo(PromptVersionSource.BUILTIN);
        assertThat(template.versionLabel()).isEqualTo("resume-analysis-v3");
        assertThat(template.contentHash()).hasSize(64);
    }

    @Test
    void invalidateReloadsActiveVersion() {
        LlmPromptVersionMapper mapper = mock(LlmPromptVersionMapper.class);
        when(mapper.selectOne(any()))
                .thenReturn(row("v1 system", "v1 user", 1, "hash-1"))
                .thenReturn(row("v2 system", "v2 user", 2, "hash-2"));

        PromptRegistry registry = new PromptRegistry(new PromptCatalog(), mapper);
        assertThat(registry.template("resume-analysis").system()).isEqualTo("v1 system");
        registry.invalidate("resume-analysis");
        assertThat(registry.template("resume-analysis").system()).isEqualTo("v2 system");

        verify(mapper, times(2)).selectOne(any());
    }

    @Test
    void builtinNeverUsesDbContent() {
        LlmPromptVersionMapper mapper = mock(LlmPromptVersionMapper.class);
        when(mapper.selectOne(any())).thenReturn(row("db system", "db user", 1, "hash-db"));

        PromptRegistry registry = new PromptRegistry(new PromptCatalog(), mapper);
        PromptTemplate template = registry.builtin("resume-analysis");

        assertThat(template.source()).isEqualTo(PromptVersionSource.BUILTIN);
        assertThat(template.system()).doesNotContain("db system");
    }

    private static LlmPromptVersion row(String system, String user, int versionNo, String hash) {
        LlmPromptVersion row = new LlmPromptVersion();
        row.setPromptKey("resume-analysis");
        row.setVersionNo(versionNo);
        row.setSystemContent(system);
        row.setUserContent(user);
        row.setContentHash(hash);
        row.setStatus("ACTIVE");
        return row;
    }
}
