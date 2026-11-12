package com.freecontent.community.content.review.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;
import java.math.BigInteger;

@Schema(requiredProperties = {"expectedVersion", "revisionId"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class SubmitReviewRequest {
    @NotNull @Positive @Max(Integer.MAX_VALUE) private BigInteger expectedVersion;
    @NotNull @Size(min = 1, max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") private String revisionId;

    @Schema(implementation = Integer.class, type = "integer", format = "int32", minimum = "1", maximum = "2147483647")
    public Integer getExpectedVersion() { return expectedVersion == null ? null : expectedVersion.intValueExact(); }
    @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")
    public String getRevisionId() { return revisionId; }
    @JsonSetter(value = "expectedVersion", nulls = Nulls.SET)
    public void setExpectedVersion(JsonNode value) {
        if (value == null || value.isNull()) expectedVersion = null;
        else {
            if (!value.isIntegralNumber()) throw new IllegalArgumentException("版本必须为整数");
            expectedVersion = value.bigIntegerValue();
        }
    }
    public void setRevisionId(String value) { revisionId = value; }
}
