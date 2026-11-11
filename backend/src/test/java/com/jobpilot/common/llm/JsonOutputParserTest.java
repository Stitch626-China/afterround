package com.jobpilot.common.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsonOutputParserTest {

    @Test
    void stripsMarkdownFence() {
        assertThat(JsonOutputParser.repair("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    void extractsJsonFromSurroundingText() {
        assertThat(JsonOutputParser.repair("结果如下：{\"a\":\"含 } 的字符串\"} 完毕"))
                .isEqualTo("{\"a\":\"含 } 的字符串\"}");
    }

    @Test
    void removesTrailingComma() {
        assertThat(JsonOutputParser.repair("{\"a\":[1,2,],\"b\":1,}")).isEqualTo("{\"a\":[1,2],\"b\":1}");
    }

    @Test
    void ignoresEscapedQuotesWhenMatchingBraces() {
        assertThat(JsonOutputParser.repair("{\"a\":\"x\\\"}y\"}")).isEqualTo("{\"a\":\"x\\\"}y\"}");
    }

    @Test
    void returnsEmptyForBlank() {
        assertThat(JsonOutputParser.repair(null)).isEmpty();
        assertThat(JsonOutputParser.repair("  ")).isEmpty();
    }
}
