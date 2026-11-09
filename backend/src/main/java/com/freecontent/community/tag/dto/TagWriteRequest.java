package com.freecontent.community.tag.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.freecontent.community.common.validation.CodePointLength;
import com.freecontent.community.tag.model.TagStatus;
import com.freecontent.community.tag.support.TagNames;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(requiredProperties = "name", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class TagWriteRequest {

    @NotNull
    @CodePointLength(min = 1, max = 30)
    private String name;
    @NotNull
    @CodePointLength(max = 200)
    private String description = "";
    @NotNull
    private TagStatus status = TagStatus.ACTIVE;

    @Schema(minLength = 1, maxLength = 30)
    public String getName() {
        return name;
    }

    @Schema(maxLength = 200, defaultValue = "", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    public String getDescription() {
        return description;
    }

    @Schema(type = "string", allowableValues = {"ACTIVE", "DISABLED"}, defaultValue = "ACTIVE", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    public TagStatus getStatus() {
        return status;
    }

    @JsonSetter(value = "name", nulls = Nulls.SET)
    public void setName(String value) {
        name = TagNames.trim(value);
    }

    @JsonSetter(value = "description", nulls = Nulls.SET)
    public void setDescription(String value) {
        description = value;
    }

    @JsonSetter(value = "status", nulls = Nulls.SET)
    public void setStatus(TagStatus value) {
        status = value;
    }
}
