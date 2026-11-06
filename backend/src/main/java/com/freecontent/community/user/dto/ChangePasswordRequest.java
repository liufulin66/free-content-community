package com.freecontent.community.user.dto;

import com.freecontent.community.common.validation.CodePointLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record ChangePasswordRequest(
        @NotNull @CodePointLength(min = 1, max = 128, message = "当前密码长度必须为1到128个字符")
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, minLength = 1, maxLength = 128) String currentPassword,
        @NotNull @CodePointLength(min = 12, max = 128, message = "新密码长度必须为12到128个字符")
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, minLength = 12, maxLength = 128) String newPassword
) {
    @Override public String toString() { return "ChangePasswordRequest[credentials=REDACTED]"; }
}
