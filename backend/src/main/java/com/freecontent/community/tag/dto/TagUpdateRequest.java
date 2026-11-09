package com.freecontent.community.tag.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.freecontent.community.common.validation.CodePointLength;
import com.freecontent.community.tag.model.TagStatus;
import com.freecontent.community.tag.support.TagNames;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.JsonNode;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE)
@Schema(description = "至少提供一个待修改字段；未提供保持原值，字段不接受null", requiredProperties = "expectedVersion",
        minProperties = 2, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class TagUpdateRequest {
    @NotNull @Positive private Integer expectedVersion;
    @CodePointLength(min = 1, max = 30) private String name;
    @CodePointLength(max = 200) private String description;
    private TagStatus status;
    private boolean nameProvided;
    private boolean descriptionProvided;
    private boolean statusProvided;

    @Schema(implementation = Integer.class, type = "integer", format = "int32", minimum = "1", maximum = "2147483647")
    public Integer getExpectedVersion() { return expectedVersion; }
    @Schema(minLength = 1, maxLength = 30, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    public String getName() { return name; }
    @Schema(maxLength = 200, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    public String getDescription() { return description; }
    @Schema(type = "string", allowableValues = {"ACTIVE", "DISABLED"}, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    public TagStatus getStatus() { return status; }

    @JsonSetter(value = "expectedVersion", nulls = Nulls.SET)
    public void setExpectedVersion(JsonNode value) {
        if (value == null || value.isNull()) expectedVersion = null;
        else {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("版本必须为32位整数");
            expectedVersion = value.intValue();
        }
    }

    @JsonSetter(value = "name", nulls = Nulls.SET)
    public void setName(String value) { nameProvided = true; name = TagNames.trim(value); }
    @JsonSetter(value = "description", nulls = Nulls.SET)
    public void setDescription(String value) { descriptionProvided = true; description = value; }
    @JsonSetter(value = "status", nulls = Nulls.SET)
    public void setStatus(TagStatus value) { statusProvided = true; status = value; }

    public boolean hasName() { return nameProvided; }
    public boolean hasDescription() { return descriptionProvided; }
    public boolean hasStatus() { return statusProvided; }
    public boolean hasMutableField() { return nameProvided || descriptionProvided || statusProvided; }
}
