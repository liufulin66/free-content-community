package com.freecontent.community.content.workspace.dto;

import com.freecontent.community.content.model.ContentKind;
import com.freecontent.community.content.model.ContentDraftData;
import com.freecontent.community.common.validation.CodePointLength;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.List;

@Schema(requiredProperties = "kind", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class CreateContentRequest {
    @NotNull private ContentKind kind;
    @NotNull @CodePointLength(max = 120) private String title = "";
    @NotNull @CodePointLength(max = 100000) private String bodyMarkdown = "";
    @Size(min = 1, max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") private String coverAssetId;
    @NotNull @Size(max = 50) private List<@NotNull @Size(min = 1, max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String> imageAssetIds = List.of();
    @Size(min = 1, max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") private String videoAssetId;
    @NotNull @Size(max = 5) private List<@NotNull @Size(min = 1, max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String> tagIds = List.of();

    public ContentDraftData toDraftData() {
        return new ContentDraftData(kind, title, bodyMarkdown, coverAssetId, imageAssetIds, videoAssetId, tagIds);
    }

    public ContentKind getKind() { return kind; }
    @Schema(maxLength = 120, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    public String getTitle() { return title; }
    @Schema(maxLength = 100000, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    public String getBodyMarkdown() { return bodyMarkdown; }
    @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    public String getCoverAssetId() { return coverAssetId; }
    @ArraySchema(maxItems = 50, uniqueItems = true, schema = @Schema(type = "string", minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"),
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED))
    public List<String> getImageAssetIds() { return imageAssetIds; }
    @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    public String getVideoAssetId() { return videoAssetId; }
    @ArraySchema(maxItems = 5, uniqueItems = true, schema = @Schema(type = "string", minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"),
            arraySchema = @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED))
    public List<String> getTagIds() { return tagIds; }

    @JsonSetter(value = "kind", nulls = Nulls.SET) public void setKind(ContentKind value) { kind = value; }
    @JsonSetter(value = "title", nulls = Nulls.SET) public void setTitle(String value) { title = value; }
    @JsonSetter(value = "bodyMarkdown", nulls = Nulls.SET) public void setBodyMarkdown(String value) { bodyMarkdown = value; }
    @JsonSetter(value = "coverAssetId", nulls = Nulls.SET) public void setCoverAssetId(String value) { coverAssetId = value; }
    @JsonSetter(value = "imageAssetIds", nulls = Nulls.SET) public void setImageAssetIds(List<String> value) { imageAssetIds = value; }
    @JsonSetter(value = "videoAssetId", nulls = Nulls.SET) public void setVideoAssetId(String value) { videoAssetId = value; }
    @JsonSetter(value = "tagIds", nulls = Nulls.SET) public void setTagIds(List<String> value) { tagIds = value; }
}
