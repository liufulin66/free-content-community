package com.freecontent.community.media.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;
import java.util.List;

@Schema(requiredProperties = "expectedVersion", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class CompleteUploadRequest {
    @NotNull @Positive private Integer expectedVersion;
    @Size(min = 1, max = 10000) private List<@Valid CompletedPart> parts;
    private boolean partsProvided;

    @Schema(type = "integer", format = "int32", minimum = "1", maximum = "2147483647")
    public Integer getExpectedVersion() { return expectedVersion; }
    @ArraySchema(minItems = 1, maxItems = 10000, schema = @Schema(implementation = CompletedPart.class),
            arraySchema = @Schema(description = "MULTIPART提交全部分片；当前SINGLE必须省略"))
    public List<CompletedPart> getParts() { return parts; }
    public boolean hasParts() { return partsProvided; }

    @JsonSetter(value = "expectedVersion", nulls = Nulls.SET)
    public void setExpectedVersion(JsonNode value) {
        if (value == null || value.isNull()) expectedVersion = null;
        else {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("版本必须为32位整数");
            expectedVersion = value.intValue();
        }
    }
    @JsonSetter(value = "parts", nulls = Nulls.SET)
    public void setParts(List<CompletedPart> value) { partsProvided = true; parts = value; }

    @Schema(requiredProperties = {"partNumber", "etag"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record CompletedPart(@NotNull @Min(1) @Max(10000) Integer partNumber,
                                @NotNull @Size(min = 1, max = 200) String etag) { }
}
