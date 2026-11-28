package com.jobpilot.modules.eval.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.job.ai.AiJobPositionParse;
import com.jobpilot.modules.llm.dto.LlmScene;
import com.jobpilot.modules.resume.ai.AiResumeAnalysis;
import com.jobpilot.modules.review.ai.AiReviewAnalysis;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 3 个评测场景的元数据与语料：场景 → 提示词 key、模型作用域、输出 DTO。
 *
 * <p>语料随包发布，启动时从 classpath 加载一次；哈希用于可比性判定。
 */
@Component
public class EvalSceneCatalog {

    public record EvalScene(String key, String name, String promptKey, LlmScene llmScene, boolean globalModel,
                            Class<?> outputType) {
    }

    private static final List<EvalScene> SCENES = List.of(
            new EvalScene("resume-analysis", "简历分析", "resume-analysis",
                    LlmScene.RESUME_ANALYZE, false, AiResumeAnalysis.class),
            new EvalScene("jd-parse", "JD 解析", "job-position-parse", null, true, AiJobPositionParse.class),
            new EvalScene("review-analyze", "复盘分析", "review-analyze",
                    LlmScene.REVIEW_ANALYZE, false, AiReviewAnalysis.class));

    private final List<EvalCase> cases;
    private final Map<String, String> hashes = new LinkedHashMap<>();

    public EvalSceneCatalog() {
        this.cases = EvalCorpus.load(new PathMatchingResourcePatternResolver());
        SCENES.forEach(scene -> hashes.put(scene.key(), hash(scene.key())));
    }

    public List<EvalScene> all() {
        return SCENES;
    }

    public EvalScene get(String key) {
        return SCENES.stream().filter(scene -> scene.key().equals(key)).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST, "不支持的评测场景: " + key));
    }

    /** 指定场景的全部语料，顺序稳定（场景顺序 + case id）。 */
    public List<EvalCase> cases(List<String> scenes) {
        return cases.stream().filter(item -> scenes.contains(item.scene())).toList();
    }

    public int caseCount(String scene) {
        return (int) cases.stream().filter(item -> item.scene().equals(scene)).count();
    }

    public String corpusHash(String scene) {
        return hashes.get(scene);
    }

    /** 运行级语料指纹：按场景顺序拼接，场景子集不同即不可比。 */
    public String combinedCorpusHash(List<String> scenes) {
        StringBuilder builder = new StringBuilder();
        SCENES.stream().map(EvalScene::key).filter(scenes::contains)
                .forEach(key -> builder.append(key).append('=').append(hashes.get(key)).append('\n'));
        return sha256(builder.toString());
    }

    private String hash(String scene) {
        StringBuilder builder = new StringBuilder();
        cases.stream().filter(item -> item.scene().equals(scene)).forEach(item -> {
            builder.append(item.id()).append('\n').append(item.input()).append('\n')
                    .append(item.meta()).append('\n').append(item.expect()).append('\n');
            // 可选背景材料同样会进入模型输入，必须计入语料指纹
            item.files().forEach((name, text) -> builder.append(name).append('=').append(text).append('\n'));
        });
        return sha256(builder.toString());
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
