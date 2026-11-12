package com.freecontent.community.content.review.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;
import java.math.BigInteger;

@Schema(requiredProperties = {"expectedVersion", "expectedReviewVersion"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class CancelReviewRequest {
    @NotNull @Positive @Max(Integer.MAX_VALUE) private BigInteger expectedVersion;
    @NotNull @Positive @Max(Integer.MAX_VALUE) private BigInteger expectedReviewVersion;
    @Schema(implementation = Integer.class, type = "integer", format = "int32", minimum = "1", maximum = "2147483647")
    public Integer getExpectedVersion() { return expectedVersion == null ? null : expectedVersion.intValueExact(); }
    @Schema(implementation = Integer.class, type = "integer", format = "int32", minimum = "1", maximum = "2147483647")
    public Integer getExpectedReviewVersion() { return expectedReviewVersion == null ? null : expectedReviewVersion.intValueExact(); }
    @JsonSetter(value = "expectedVersion", nulls = Nulls.SET) public void setExpectedVersion(JsonNode value) { expectedVersion = integer(value); }
    @JsonSetter(value = "expectedReviewVersion", nulls = Nulls.SET) public void setExpectedReviewVersion(JsonNode value) { expectedReviewVersion = integer(value); }
    private BigInteger integer(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber()) throw new IllegalArgumentException("版本必须为整数");
        return value.bigIntegerValue();
    }
}
