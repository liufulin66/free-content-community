package com.freecontent.community.user.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.freecontent.community.common.validation.CodePointLength;
import com.freecontent.community.user.validation.ValidProfileUpdate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import java.math.BigInteger;

/** Setter仅在字段出现时调用，显式null也保留为一次修改。 */
@ValidProfileUpdate
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE)
@Schema(description = "至少提供一个待修改字段；未提供保持原值，头像允许null清空",
        requiredProperties = "expectedVersion", minProperties = 2,
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class UpdateProfileRequest {
    // 先保留整数原值完成范围校验，避免越界值在JSON绑定时被误判为类型错误。
    @NotNull @Positive @Max(Integer.MAX_VALUE) private BigInteger expectedVersion;
    // 昵称、个人简介、头像允许null清空，未提供保持原值
    @CodePointLength(min = 1, max = 40, message = "昵称长度必须为1到40个字符") private String displayName;
    @CodePointLength(max = 500, message = "个人简介最多500个字符") private String bio;
    @Size(min = 1, max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") private String avatarAssetId;
    private boolean displayNameProvided;
    private boolean bioProvided;
    private boolean avatarAssetIdProvided;

    // 32位整数，客户端必须提供当前版本号以防止并发修改覆盖
    @Schema(implementation = Integer.class, type = "integer", format = "int32", minimum = "1", maximum = "2147483647")
    public Integer getExpectedVersion() { return expectedVersion == null ? null : expectedVersion.intValueExact(); }

    // 昵称、个人简介、头像允许null清空，未提供保持原值
    @Schema(type = "string", minLength = 1, maxLength = 40)
    public String getDisplayName() { return displayName; }

    // 个人简介，最多500个字符
    @Schema(type = "string", maxLength = 500)
    public String getBio() { return bio; }

    // 头像资产ID，允许null清空，未提供保持原值
    @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")
    public String getAvatarAssetId() { return avatarAssetId; }

    // 不使用get/is形式暴露内部标记，客户端不能提交这些标记来改变PATCH语义。
    @JsonSetter(value = "expectedVersion", nulls = Nulls.SET)
    public void setExpectedVersion(JsonNode value) {
        if (value == null || value.isNull()) expectedVersion = null;
        else {
            if (!value.isIntegralNumber()) throw new IllegalArgumentException("版本必须为整数");
            expectedVersion = value.bigIntegerValue();
        }
    }

    
    @JsonSetter(value = "displayName", nulls = Nulls.SET)
    public void setDisplayName(JsonNode value) {
        displayNameProvided = true;
        displayName = text(value);
    }

    @JsonSetter(value = "bio", nulls = Nulls.SET)
    public void setBio(JsonNode value) {
        bioProvided = true;
        bio = text(value);
    }

    @JsonSetter(value = "avatarAssetId", nulls = Nulls.SET)
    public void setAvatarAssetId(JsonNode value) {
        avatarAssetIdProvided = true;
        avatarAssetId = text(value);
    }

    // 不使用get/is形式暴露内部标记，客户端不能提交这些标记来改变PATCH语义。
    public boolean hasDisplayName() { return displayNameProvided; }
    public boolean hasBio() { return bioProvided; }
    public boolean hasAvatarAssetId() { return avatarAssetIdProvided; }
    public boolean hasMutableField() { return displayNameProvided || bioProvided || avatarAssetIdProvided; }

    private static String text(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw new IllegalArgumentException("资料字段必须为字符串或明确允许的null");
        return value.stringValue();
    }

    @Override public String toString() { return "UpdateProfileRequest[values=REDACTED]"; }
}
