package com.jobpilot.modules.eval.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** 一份评测语料：输入正文 + 场景参数 + 断言；附属文件随语料一起打进 classpath。 */
public record EvalCase(String scene, String id, String input, JsonNode meta, JsonNode expect,
                       Map<String, String> files) {

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

    /** 读取 jd.txt / company-intro.txt / resume.txt；不存在时返回 null。 */
    public String optionalFile(String name) {
        return files.get(name);
    }
}
