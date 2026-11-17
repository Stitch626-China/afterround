package com.jobpilot.modules.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.llm.dto.BindSceneModelRequest;
import com.jobpilot.modules.llm.dto.LlmScene;
import com.jobpilot.modules.llm.entity.LlmGlobalSetting;
import com.jobpilot.modules.llm.entity.LlmModel;
import com.jobpilot.modules.llm.entity.LlmProvider;
import com.jobpilot.modules.llm.entity.LlmSceneSetting;
import com.jobpilot.modules.llm.mapper.LlmGlobalSettingMapper;
import com.jobpilot.modules.llm.mapper.LlmModelMapper;
import com.jobpilot.modules.llm.mapper.LlmProviderMapper;
import com.jobpilot.modules.llm.mapper.LlmSceneSettingMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 场景绑定：未配置回落全局、显式绑定校验 Provider/Model、解绑幂等。 */
@ExtendWith(MockitoExtension.class)
class LlmSceneServiceTest {

    @Mock
    private LlmSceneSettingMapper sceneMapper;
    @Mock
    private LlmProviderMapper providerMapper;
    @Mock
    private LlmModelMapper modelMapper;
    @Mock
    private LlmGlobalSettingMapper globalMapper;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(LlmSceneSetting.class, LlmProvider.class, LlmModel.class);
    }

    private LlmSceneService service() {
        return new LlmSceneService(sceneMapper, providerMapper, modelMapper, globalMapper);
    }

    private static LlmGlobalSetting global() {
        LlmGlobalSetting global = new LlmGlobalSetting();
        global.setDefaultProviderKey("p");
        global.setDefaultModel("m");
        return global;
    }

    private static LlmSceneSetting setting() {
        LlmSceneSetting setting = new LlmSceneSetting();
        setting.setId(1L);
        setting.setScene("RESUME_ANALYZE");
        setting.setProviderKey("q");
        setting.setModel("n");
        setting.setUpdatedAt(OffsetDateTime.now());
        return setting;
    }

    @Test
    void listMergesExplicitBindingsWithGlobalFallback() {
        when(globalMapper.selectById(1)).thenReturn(global());
        when(sceneMapper.selectList(any())).thenReturn(List.of(setting()));

        var bindings = service().list();

        assertThat(bindings).hasSize(LlmScene.values().length);
        var explicit = bindings.stream().filter(b -> b.scene() == LlmScene.RESUME_ANALYZE).findFirst().orElseThrow();
        assertThat(explicit.inherited()).isFalse();
        assertThat(explicit.effectiveProviderKey()).isEqualTo("q");
        var inherited = bindings.stream().filter(b -> b.scene() == LlmScene.CHAT).findFirst().orElseThrow();
        assertThat(inherited.inherited()).isTrue();
        assertThat(inherited.effectiveProviderKey()).isEqualTo("p");
        assertThat(inherited.providerKey()).isNull();
    }

    @Test
    void bindRejectsUnknownDisabledProviderOrForeignModel() {
        when(providerMapper.selectOne(any())).thenReturn(null);
        assertCode(() -> service().bind(LlmScene.JOB_MATCH, new BindSceneModelRequest("x", "y")),
                ErrorCode.BAD_REQUEST);

        LlmProvider provider = new LlmProvider();
        provider.setId(10L);
        provider.setEnabled(false);
        when(providerMapper.selectOne(any())).thenReturn(provider);
        assertCode(() -> service().bind(LlmScene.JOB_MATCH, new BindSceneModelRequest("p", "m")),
                ErrorCode.BAD_REQUEST);

        provider.setEnabled(true);
        when(modelMapper.selectOne(any())).thenReturn(null);
        assertCode(() -> service().bind(LlmScene.JOB_MATCH, new BindSceneModelRequest("p", "m")),
                ErrorCode.BAD_REQUEST);

        LlmModel model = new LlmModel();
        model.setEnabled(false);
        when(modelMapper.selectOne(any())).thenReturn(model);
        assertCode(() -> service().bind(LlmScene.JOB_MATCH, new BindSceneModelRequest("p", "m")),
                ErrorCode.BAD_REQUEST);
    }

    @Test
    void bindInsertsOrUpdatesExistingBinding() {
        LlmProvider provider = new LlmProvider();
        provider.setId(10L);
        provider.setEnabled(true);
        LlmModel model = new LlmModel();
        model.setEnabled(true);
        when(providerMapper.selectOne(any())).thenReturn(provider);
        when(modelMapper.selectOne(any())).thenReturn(model);
        when(globalMapper.selectById(1)).thenReturn(global());

        when(sceneMapper.selectOne(any())).thenReturn(null);
        var created = service().bind(LlmScene.JOB_MATCH, new BindSceneModelRequest("p", "m"));
        assertThat(created.inherited()).isFalse();
        verify(sceneMapper).insert(any(LlmSceneSetting.class));

        when(sceneMapper.selectOne(any())).thenReturn(setting());
        var updated = service().bind(LlmScene.RESUME_ANALYZE, new BindSceneModelRequest("p", "m"));
        assertThat(updated.providerKey()).isEqualTo("p");
        verify(sceneMapper).updateById(any(LlmSceneSetting.class));
    }

    @Test
    void unbindDeletesAndResolveFallsBack() {
        when(globalMapper.selectById(1)).thenReturn(global());
        when(sceneMapper.selectOne(any())).thenReturn(null);

        var binding = service().unbind(LlmScene.JOB_MATCH);
        assertThat(binding.inherited()).isTrue();
        verify(sceneMapper).delete(any());

        var resolved = service().resolve(LlmScene.JOB_MATCH);
        assertThat(resolved.providerKey()).isEqualTo("p");
        assertThat(resolved.model()).isEqualTo("m");
    }

    @Test
    void resolvePrefersSceneBindingAndFailsWithoutDefaults() {
        when(sceneMapper.selectOne(any())).thenReturn(setting());
        var resolved = service().resolve(LlmScene.RESUME_ANALYZE);
        assertThat(resolved.providerKey()).isEqualTo("q");
        assertThat(resolved.model()).isEqualTo("n");

        when(sceneMapper.selectOne(any())).thenReturn(null);
        when(globalMapper.selectById(1)).thenReturn(null);
        assertCode(() -> service().resolve(LlmScene.JOB_MATCH), ErrorCode.BAD_REQUEST);
        assertCode(() -> service().resolveGlobal(), ErrorCode.BAD_REQUEST);

        LlmGlobalSetting blank = new LlmGlobalSetting();
        blank.setDefaultProviderKey(" ");
        when(globalMapper.selectById(1)).thenReturn(blank);
        assertCode(() -> service().resolveGlobal(), ErrorCode.BAD_REQUEST);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }
}
