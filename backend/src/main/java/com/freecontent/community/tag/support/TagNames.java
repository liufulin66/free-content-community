package com.freecontent.community.tag.support;

/** 只去除首尾空白，保留内部空白和显示大小写；名称相等性由明确的数据库排序规则保证。 */
public final class TagNames {
    private TagNames() { }
    public static String trim(String value) {
        if (value == null) return null;
        int start = 0, end = value.length();
        while (start < end && whitespace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (start < end && whitespace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }
    private static boolean whitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85 || codePoint == 0xfeff;
    }
}
