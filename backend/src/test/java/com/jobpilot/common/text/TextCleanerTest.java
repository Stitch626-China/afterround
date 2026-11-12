package com.jobpilot.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextCleanerTest {

    @Test
    void normalizesLineEndingsAndControlChars() {
        String cleaned = TextCleaner.clean("第一行\r\n第二行\r\u0007第三行\tEND");

        assertThat(cleaned).isEqualTo("第一行\n第二行\n第三行\tEND");
    }

    @Test
    void removesImageReferencesAndTrailingSpaces() {
        String cleaned = TextCleaner.clean("正文   \n![图](http://x/a.png)\n更多");

        assertThat(cleaned).isEqualTo("正文\n\n更多");
    }

    @Test
    void collapsesExcessiveBlankLines() {
        assertThat(TextCleaner.clean("A\n\n\n\nB")).isEqualTo("A\n\nB");
    }

    @Test
    void blankInputReturnsEmpty() {
        assertThat(TextCleaner.clean(null)).isEmpty();
        assertThat(TextCleaner.clean("   ")).isEmpty();
    }
}
