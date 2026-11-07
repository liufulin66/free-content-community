package com.freecontent.community.media.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.freecontent.community.common.validation.CodePointLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.freecontent.community.media.validation.UploadSizeDeserializer;
import java.util.Locale;

@Getter
@Setter
@Schema(requiredProperties = {"scene", "fileName", "contentType", "sizeBytes"}, additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class CreateUploadRequest {
    public enum Scene { AVATAR, CONTENT_IMAGE, CONTENT_VIDEO, REPORT_ATTACHMENT }
    @NotNull @Schema(type = "string", allowableValues = {"AVATAR", "CONTENT_IMAGE", "CONTENT_VIDEO", "REPORT_ATTACHMENT"}) private Scene scene;
    @NotNull @CodePointLength(min = 1, max = 255) @Schema(type = "string", minLength = 1, maxLength = 255) private String fileName;
    @NotNull @CodePointLength(min = 1, max = 100) @Schema(type = "string", minLength = 1, maxLength = 100) private String contentType;
    @NotNull @Min(1) @Max(9007199254740991L)
    @JsonDeserialize(using = UploadSizeDeserializer.class)
    @Schema(types = {"integer"}, format = "int64", minimum = "1", maximum = "9007199254740991")
    private Long sizeBytes;
    @Pattern(regexp = "^[a-fA-F0-9]{64}$") @Schema(type = "string", pattern = "^[a-fA-F0-9]{64}$") private String sha256;

    @Schema(types = {"integer"}, format = "int64", minimum = "1", maximum = "9007199254740991")
    public Long getSizeBytes() { return sizeBytes; }
    @Schema(type = "string", allowableValues = {"AVATAR", "CONTENT_IMAGE", "CONTENT_VIDEO", "REPORT_ATTACHMENT"})
    public Scene getScene() { return scene; }
    @Schema(type = "string", minLength = 1, maxLength = 255)
    public String getFileName() { return fileName; }
    @Schema(type = "string", minLength = 1, maxLength = 100)
    public String getContentType() { return contentType; }
    @Schema(type = "string", pattern = "^[a-fA-F0-9]{64}$")
    public String getSha256() { return sha256; }

    @JsonSetter("scene")
    public void setScene(JsonNode value) { String text = text(value); scene = text == null ? null : Scene.valueOf(text); }
    @JsonSetter("fileName")
    public void setFileName(JsonNode value) { fileName = text(value); }
    @JsonSetter("contentType")
    public void setContentType(JsonNode value) { String text = text(value); contentType = text == null ? null : text.trim().toLowerCase(Locale.ROOT); }
    @JsonSetter("sha256")
    public void setSha256(JsonNode value) {
        String text = text(value);
        if (text == null) throw new IllegalArgumentException("提供sha256时必须为字符串");
        sha256 = text.toLowerCase(Locale.ROOT);
    }
    private static String text(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw new IllegalArgumentException("字段必须为字符串");
        return value.asString();
    }
}
