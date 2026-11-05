package com.freecontent.community.auth.dto;

import com.freecontent.community.auth.model.EmailChallengePurpose;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateEmailChallengeRequest(
        @NotNull(message = "验证码用途不能为空")
        EmailChallengePurpose purpose,
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 254, message = "邮箱长度不能超过254个字符")
        @Pattern(regexp = "^[\\x00-\\x7F]+$", message = "邮箱只能包含ASCII字符")
        String email
) {
    public CreateEmailChallengeRequest {
        if (email != null) {
            email = email.trim();
        }
    }
}
