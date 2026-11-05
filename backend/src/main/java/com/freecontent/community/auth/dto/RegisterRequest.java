package com.freecontent.community.auth.dto;

import com.freecontent.community.common.validation.CodePointLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254)
        @Pattern(regexp = "^[\\x00-\\x7F]+$")
        String email,
        @NotNull
        @CodePointLength(min = 12, max = 128, message = "密码长度必须为12到128个字符")
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, minLength = 12, maxLength = 128,
                description = "按Unicode字符计数，密码原样参与哈希，不截断、不trim")
        String password,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$")
        String challengeId,
        @NotNull @Pattern(regexp = "^[0-9]{6}$")
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        String code,
        @NotBlank
        @CodePointLength(min = 1, max = 40, message = "昵称长度必须为1到40个字符")
        @Schema(minLength = 1, maxLength = 40)
        String displayName
) {
    public RegisterRequest {
        if (email != null) {
            email = email.trim();
        }
    }

    @Override
    public String toString() {
        return "RegisterRequest[credentials=REDACTED]";
    }
}
