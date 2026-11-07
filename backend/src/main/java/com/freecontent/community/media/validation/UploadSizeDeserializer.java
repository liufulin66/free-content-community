package com.freecontent.community.media.validation;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 保持DTO字段为Long，同时拒绝小数、数字字符串以及超出Long范围的值。 */
public class UploadSizeDeserializer extends ValueDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) throw new IllegalArgumentException("文件大小必须为整数");
        return parser.getLongValue();
    }
}
