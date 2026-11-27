package com.jobpilot.eval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** 一份评测语料：输入正文 + 场景参数 + 断言。 */
public record EvalCase(String scene, String id, Path dir, String input, JsonNode meta, JsonNode expect) {

    public String text(String field) {
        return meta.path(field).asText(null);
    }

    public boolean flag(String field) {
        return expect.path(field).asBoolean(false);
    }

    public int intValue(String field, int fallback) {
        return expect.has(field) ? expect.path(field).asInt() : fallback;
    }

    public List<String> terms(String field) {
        List<String> values = new ArrayList<>();
        expect.path(field).forEach(node -> values.add(node.asText()));
        return values;
    }

    public String optionalFile(String name) {
        Path file = dir.resolve(name);
        return java.nio.file.Files.isRegularFile(file) ? read(file) : null;
    }

    private static String read(Path file) {
        try {
            return java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("读取语料文件失败: " + file, ex);
        }
    }
}
