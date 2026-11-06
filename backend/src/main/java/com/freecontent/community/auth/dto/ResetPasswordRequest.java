package com.freecontent.community.auth.dto;

import com.freecontent.community.common.validation.CodePointLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

public record ResetPasswordRequest(
        @NotBlank @Email @Size(max = 254) @Pattern(regexp = "^[\\x00-\\x7F]+$") String email,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String challengeId,
        @NotNull @Pattern(regexp = "^[0-9]{6}$")
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String code,
        @NotNull @CodePointLength(min = 12, max = 128, message = "新密码长度必须为12到128个字符")
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, minLength = 12, maxLength = 128) String newPassword
) {
    public ResetPasswordRequest {
        if (email != null) email = email.trim();
    }

    @Override
    public String toString() { return "ResetPasswordRequest[credentials=REDACTED]"; }
}
