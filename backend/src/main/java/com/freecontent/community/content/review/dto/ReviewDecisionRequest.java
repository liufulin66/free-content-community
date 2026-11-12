package com.freecontent.community.content.review.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.freecontent.community.common.validation.CodePointLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;
import java.math.BigInteger;

@Schema(requiredProperties = {"expectedContentVersion", "expectedReviewVersion", "decision", "reason"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class ReviewDecisionRequest {
    public enum Decision { APPROVE, REJECT }
    @NotNull @Positive @Max(Integer.MAX_VALUE) private BigInteger expectedContentVersion;
    @NotNull @Positive @Max(Integer.MAX_VALUE) private BigInteger expectedReviewVersion;
    @NotNull private Decision decision;
    @NotBlank @CodePointLength(min = 1, max = 1000) private String reason;

    @Schema(implementation = Integer.class, type = "integer", format = "int32", minimum = "1", maximum = "2147483647")
    public Integer getExpectedContentVersion() { return expectedContentVersion == null ? null : expectedContentVersion.intValueExact(); }
    @Schema(implementation = Integer.class, type = "integer", format = "int32", minimum = "1", maximum = "2147483647")
    public Integer getExpectedReviewVersion() { return expectedReviewVersion == null ? null : expectedReviewVersion.intValueExact(); }
    public Decision getDecision() { return decision; }
    @Schema(minLength = 1, maxLength = 1000) public String getReason() { return reason; }
    @JsonSetter(value = "expectedContentVersion", nulls = Nulls.SET) public void setExpectedContentVersion(JsonNode value) { expectedContentVersion = integer(value); }
    @JsonSetter(value = "expectedReviewVersion", nulls = Nulls.SET) public void setExpectedReviewVersion(JsonNode value) { expectedReviewVersion = integer(value); }
    public void setDecision(Decision value) { decision = value; }
    public void setReason(String value) { reason = value; }
    private BigInteger integer(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber()) throw new IllegalArgumentException("版本必须为整数");
        return value.bigIntegerValue();
    }
}
