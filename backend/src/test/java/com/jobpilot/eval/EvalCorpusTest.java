package com.jobpilot.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.jobpilot.modules.eval.core.EvalCase;
import com.jobpilot.modules.eval.core.EvalCorpus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** 语料校验单元测试 + 真实语料合规测试（默认 mvn test 执行，不调用模型）。 */
class EvalCorpusTest {

    @TempDir
    Path temp;

    private void writeCase(String scene, String name, String meta, String expect, String input) throws Exception {
        Path dir = temp.resolve(scene).resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("input.txt"), input, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("meta.json"), meta, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("expect.json"), expect, StandardCharsets.UTF_8);
    }

    @Test
    void loadsValidMinimalCase() throws Exception {
        writeCase("resume-analysis", "001-x",
                "{\"scene\":\"resume-analysis\",\"sourceType\":\"synthetic\",\"sanitized\":true}",
                "{\"requiredFields\":[\"technicalDepth\"]}", "三年 Java 经验。");
        List<EvalCase> cases = EvalCorpus.load(temp);
        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).id()).isEqualTo("001-x");
    }

    @Test
    void reportsMissingFile() throws Exception {
        writeCase("resume-analysis", "001-x",
                "{\"scene\":\"resume-analysis\",\"sourceType\":\"synthetic\",\"sanitized\":true}",
                "{\"requiredFields\":[\"technicalDepth\"]}", "三年 Java 经验。");
        Files.delete(temp.resolve("resume-analysis/001-x/expect.json"));
        assertThat(EvalCorpus.validate(temp)).anyMatch(i -> i.contains("缺少 expect.json"));
    }

    @Test
    void reportsUnknownExpectKeyAndSceneMismatch() throws Exception {
        writeCase("resume-analysis", "001-x",
                "{\"scene\":\"review-analyze\",\"sourceType\":\"synthetic\",\"sanitized\":true}",
                "{\"requiredFields\":[\"technicalDepth\"],\"unknown\":1}", "三年 Java 经验。");
        List<String> issues = EvalCorpus.validate(temp);
        assertThat(issues).anyMatch(i -> i.contains("未知键 unknown"));
        assertThat(issues).anyMatch(i -> i.contains("scene=review-analyze"));
    }

    @Test
    void reportsBomAndPrivacyPattern() throws Exception {
        Path dir = temp.resolve("resume-analysis/001-x");
        Files.createDirectories(dir);
        byte[] prefix = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "联系 13800138000".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(body, 0, all, prefix.length, body.length);
        Files.write(dir.resolve("input.txt"), all);
        Files.writeString(dir.resolve("meta.json"),
                "{\"scene\":\"resume-analysis\",\"sourceType\":\"synthetic\",\"sanitized\":true}");
        Files.writeString(dir.resolve("expect.json"), "{\"requiredFields\":[\"technicalDepth\"]}");
        List<String> issues = EvalCorpus.validate(temp);
        assertThat(issues).anyMatch(i -> i.contains("BOM"));
        assertThat(issues).anyMatch(i -> i.contains("隐私正则"));
    }

    @Test
    void classpathCorpusIsCompliantAndLoads27Cases() {
        var resolver = new PathMatchingResourcePatternResolver();
        assertThat(EvalCorpus.validate(resolver)).isEmpty();
        List<EvalCase> cases = EvalCorpus.load(resolver);
        assertThat(cases).hasSize(27);
        assertThat(cases).extracting(EvalCase::scene).containsOnly("resume-analysis", "review-analyze", "jd-parse");
        assertThat(cases.get(0).input()).isNotBlank();
    }
}
