package com.freecontent.community.content.browse.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentSummaryTest {
    @Test void extractsTextWithoutMarkdownImageOrHtmlNodes() {
        assertEquals("标题 正文 强调 链接 内联代码 代码块",
                new ContentSummary().summarize("# 标题\n\n正文 **强调** [链接](https://example.test) ![图片](asset:image) <b>内联代码</b>\n\n```java\n代码块\n```"));
    }
    @Test void preservesUnicodeCodePointsAndCollapsesWhitespace() {
        String result = new ContentSummary().summarize("😀".repeat(301));
        assertEquals("😀".repeat(300), result);
        assertEquals("a b c", new ContentSummary().summarize("a\n\nb\u00a0 c"));
        assertEquals("", new ContentSummary().summarize(""));
    }
}
