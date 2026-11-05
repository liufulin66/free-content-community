package com.freecontent.community.auth.dto;

import com.freecontent.community.common.validation.CodePointLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

public record LoginRequest(
        @NotBlank @Email @Size(max = 254) @Pattern(regexp = "^[\\x00-\\x7F]+$") String email,
        @NotNull @CodePointLength(min = 1, max = 128, message = "密码长度必须为1到128个字符")
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY, minLength = 1, maxLength = 128) String password,
        @CodePointLength(max = 80, message = "设备名称最多80个字符") String deviceLabel
) {
    public LoginRequest {
        if (email != null) email = email.trim();
    }

    @Override
    public String toString() {
        return "LoginRequest[credentials=REDACTED]";
    }
}
