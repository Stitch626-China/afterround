package com.jobpilot.modules.prompt.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jobpilot.modules.prompt.catalog.PromptCatalog;
import com.jobpilot.modules.prompt.catalog.PromptSpec;
import com.jobpilot.modules.prompt.catalog.PromptVersionSource;
import com.jobpilot.modules.prompt.entity.LlmPromptVersion;
import com.jobpilot.modules.prompt.mapper.LlmPromptVersionMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 提示词运行时注册表：DB ACTIVE 优先，查不到回退 classpath 内置默认。
 *
 * <p>发布 / 回滚后调用 {@link #invalidate(String)} 精确失效，下一次调用即使用新内容。
 */
@Component
public class PromptRegistry {

    private static final String ACTIVE = "ACTIVE";
    private final PromptCatalog catalog;
    private final LlmPromptVersionMapper mapper;
    private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();
    private final Map<String, PromptTemplate> builtinCache = new ConcurrentHashMap<>();

    public PromptRegistry(PromptCatalog catalog, LlmPromptVersionMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
    }

    /** 测试/CLI 用：不查 DB，只读内置模板。 */
    public static PromptRegistry builtinOnly() {
        return new PromptRegistry(new PromptCatalog(), null);
    }

    /** 当前生效模板。 */
    public PromptTemplate template(String key) {
        return cache.computeIfAbsent(key, this::loadActive);
    }

    /** 代码内置默认模板，供 diff / 恢复内置使用；不受 DB 版本影响。 */
    public PromptTemplate builtin(String key) {
        return builtinCache.computeIfAbsent(key, this::loadBuiltin);
    }

    /** 分析结果记录的版本标签：内置为 vN，DB 为 <key>-dbN。 */
    public String version(String key) {
        return template(key).versionLabel();
    }

    /** 发布 / 回滚后精确失效。 */
    public void invalidate(String key) {
        cache.remove(key);
    }

    private PromptTemplate loadActive(String key) {
        PromptSpec spec = catalog.get(key);
        if (mapper != null) {
            LlmPromptVersion row = mapper.selectOne(Wrappers.<LlmPromptVersion>lambdaQuery()
                    .eq(LlmPromptVersion::getPromptKey, key)
                    .eq(LlmPromptVersion::getStatus, ACTIVE));
            if (row != null) {
                return new PromptTemplate(key, row.getSystemContent(), row.getUserContent(),
                        key + "-db" + row.getVersionNo(), row.getContentHash(), PromptVersionSource.DB);
            }
        }
        return loadBuiltin(spec);
    }

    private PromptTemplate loadBuiltin(String key) {
        return loadBuiltin(catalog.get(key));
    }

    private PromptTemplate loadBuiltin(PromptSpec spec) {
        String system = read(spec.systemFile());
        String user = read(spec.userFile());
        return new PromptTemplate(spec.key(), system, user, spec.builtinVersion(), hash(system, user),
                PromptVersionSource.BUILTIN);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("加载 Prompt 模板失败: " + path, ex);
        }
    }

    /** 与 PromptService 保存草稿时使用的哈希保持一致。 */
    public static String hash(String system, String user) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((system + "\n---\n" + user).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
