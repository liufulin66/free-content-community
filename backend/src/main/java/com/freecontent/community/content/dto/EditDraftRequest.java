package com.freecontent.community.content.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.freecontent.community.common.validation.CodePointLength;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.util.List;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE)
@Schema(description = "至少提供一个修改字段；省略保持原值，封面和视频可用null清空",requiredProperties = "expectedVersion", minProperties = 2, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class EditDraftRequest {
    @NotNull @Positive @Max(Integer.MAX_VALUE) private BigInteger expectedVersion;
    @CodePointLength(max = 120) private String title;
    @CodePointLength(max = 100000) private String bodyMarkdown;
    @Size(min = 1,max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") private String coverAssetId;
    @Size(max = 50) private List<@NotNull @Size(min = 1,max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String> imageAssetIds;
    @Size(min = 1,max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") private String videoAssetId;
    @Size(max = 5) private List<@NotNull @Size(min = 1,max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String> tagIds;
    private boolean titleProvided,bodyProvided,coverProvided,imagesProvided,videoProvided,tagsProvided;

    @Schema(description = "来自工作区响应的内容聚合版本；过期返回CONTENT_VERSION_CONFLICT",implementation = Integer.class,type = "integer",format = "int32",minimum = "1",maximum = "2147483647")
    public Integer getExpectedVersion() { return expectedVersion == null ? null : expectedVersion.intValueExact(); }
    @Schema(maxLength = 120,requiredMode = Schema.RequiredMode.NOT_REQUIRED) public String getTitle() { return title; }
    @Schema(maxLength = 100000,requiredMode = Schema.RequiredMode.NOT_REQUIRED) public String getBodyMarkdown() { return bodyMarkdown; }
    @Schema(types = {"string","null"},minLength = 1,maxLength = 64,pattern = "^[A-Za-z0-9_-]+$",requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    public String getCoverAssetId() { return coverAssetId; }
    @ArraySchema(maxItems = 50,uniqueItems = true,schema = @Schema(type = "string",minLength = 1,maxLength = 64,pattern = "^[A-Za-z0-9_-]+$"),arraySchema = @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED))
    public List<String> getImageAssetIds() { return imageAssetIds; }
    @Schema(types = {"string","null"},minLength = 1,maxLength = 64,pattern = "^[A-Za-z0-9_-]+$",requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    public String getVideoAssetId() { return videoAssetId; }
    @ArraySchema(maxItems = 5,uniqueItems = true,schema = @Schema(type = "string",minLength = 1,maxLength = 64,pattern = "^[A-Za-z0-9_-]+$"),arraySchema = @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED))
    public List<String> getTagIds() { return tagIds; }

    @JsonSetter(value = "expectedVersion",nulls = Nulls.SET) public void setExpectedVersion(JsonNode value) {
        if (value == null || value.isNull()) expectedVersion = null;
        else { if (!value.isIntegralNumber()) throw new IllegalArgumentException("版本必须为整数"); expectedVersion = value.bigIntegerValue(); }
    }
    @JsonSetter(value = "title",nulls = Nulls.SET) public void setTitle(String value) { titleProvided = true; title = value; }
    @JsonSetter(value = "bodyMarkdown",nulls = Nulls.SET) public void setBodyMarkdown(String value) { bodyProvided = true; bodyMarkdown = value; }
    @JsonSetter(value = "coverAssetId",nulls = Nulls.SET) public void setCoverAssetId(String value) { coverProvided = true; coverAssetId = value; }
    @JsonSetter(value = "imageAssetIds",nulls = Nulls.SET) public void setImageAssetIds(List<String> value) { imagesProvided = true; imageAssetIds = value; }
    @JsonSetter(value = "videoAssetId",nulls = Nulls.SET) public void setVideoAssetId(String value) { videoProvided = true; videoAssetId = value; }
    @JsonSetter(value = "tagIds",nulls = Nulls.SET) public void setTagIds(List<String> value) { tagsProvided = true; tagIds = value; }
    public boolean hasTitle() { return titleProvided; }
    public boolean hasBodyMarkdown() { return bodyProvided; }
    public boolean hasCoverAssetId() { return coverProvided; }
    public boolean hasImageAssetIds() { return imagesProvided; }
    public boolean hasVideoAssetId() { return videoProvided; }
    public boolean hasTagIds() { return tagsProvided; }
    public boolean hasMutableField() { return titleProvided || bodyProvided || coverProvided || imagesProvided || videoProvided || tagsProvided; }
}
