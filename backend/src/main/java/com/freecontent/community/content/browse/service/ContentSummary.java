package com.freecontent.community.content.browse.service;

import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

/** 摘要仅来自已发布正文；不渲染 HTML，不截断 UTF-16 代理对。 */
@Component
public class ContentSummary {
    private final Parser parser = Parser.builder().build();

    public String summarize(String markdown) {
        StringBuilder text = new StringBuilder();
        parser.parse(markdown).accept(new AbstractVisitor() {
            @Override public void visit(Text node) { text.append(node.getLiteral()); }
            @Override public void visit(Code node) { text.append(node.getLiteral()); }
            @Override public void visit(Image node) { }
            @Override public void visit(HtmlInline node) { }
            @Override public void visit(HtmlBlock node) { }
            @Override public void visit(SoftLineBreak node) { text.append(' '); }
            @Override public void visit(HardLineBreak node) { text.append(' '); }
            @Override public void visit(Paragraph node) { visitChildren(node); text.append(' '); }
            @Override public void visit(Heading node) { visitChildren(node); text.append(' '); }
            @Override public void visit(ListItem node) { visitChildren(node); text.append(' '); }
            @Override public void visit(FencedCodeBlock node) { text.append(node.getLiteral()).append(' '); }
            @Override public void visit(IndentedCodeBlock node) { text.append(node.getLiteral()).append(' '); }
        });
        StringBuilder normalized = new StringBuilder();
        boolean[] space = {false};
        text.codePoints().forEach(point -> {
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                space[0] = normalized.length() > 0;
            } else {
                if (space[0]) normalized.append(' ');
                normalized.appendCodePoint(point);
                space[0] = false;
            }
        });
        String result = normalized.toString();
        return result.codePointCount(0, result.length()) <= 300 ? result
                : result.substring(0, result.offsetByCodePoints(0, 300)).stripTrailing();
    }
}
