package com.freecontent.community.auth.controller;

import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.dto.ResetPasswordRequest;
import com.freecontent.community.auth.service.*;
import com.freecontent.community.common.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(SecurityConfiguration.class)
class PasswordResetEndpointTest {
    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @MockitoBean private EmailChallengeService emailChallengeService;
    @MockitoBean private RegistrationService registrationService;
    @MockitoBean private LoginService loginService;
    @MockitoBean private LogoutService logoutService;
    @MockitoBean private PasswordResetService service;
    @MockitoBean private SessionAuthenticationService authentication;

    @Test
    void publicResetUsesConnectionAddressAndReturnsEmpty204WithoutSession() throws Exception {
        mvc.perform(post("/api/v1/auth/password-resets").contentType(MediaType.APPLICATION_JSON)
                        .content(body(" NewPassword! ")).header("Authorization", "Bearer old-token")
                        .header("X-Forwarded-For", "203.0.113.8"))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(service).reset(new ResetPasswordRequest("Creator@Example.test", "challenge1", "000123", " NewPassword! "), "127.0.0.1");
        verifyNoInteractions(authentication, loginService, logoutService);
    }

    @Test
    void passwordLengthsUseUnicodeCodePointsAndPasswordWhitespaceIsPreserved() throws Exception {
        for (String password : List.of("😀".repeat(12), "😀".repeat(128), " ".repeat(12))) {
            mvc.perform(post("/api/v1/auth/password-resets").contentType(MediaType.APPLICATION_JSON).content(body(password)))
                    .andExpect(status().isNoContent());
        }
        for (String password : List.of("😀".repeat(11), "😀".repeat(129), "")) {
            mvc.perform(post("/api/v1/auth/password-resets").contentType(MediaType.APPLICATION_JSON).content(body(password)))
                    .andExpect(status().isUnprocessableContent());
        }
        verify(service, times(3)).reset(any(), anyString());
    }

    @Test
    void rejectsMissingFieldsInvalidChallengeAndCodeBeforeBusinessWork() throws Exception {
        for (Map<String, String> values : List.of(
                Map.of("email", "a@example.test", "challengeId", "challenge1", "code", "123456"),
                Map.of("email", "a@example.test", "challengeId", "中文", "code", "123456", "newPassword", "NewPassword!2026"),
                Map.of("email", "a@example.test", "challengeId", "challenge1", "code", "12345", "newPassword", "NewPassword!2026"),
                Map.of("email", "用户@example.test", "challengeId", "challenge1", "code", "123456", "newPassword", "NewPassword!2026"))) {
            mvc.perform(post("/api/v1/auth/password-resets").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(values)))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void clientCannotOverrideChallengePurpose() throws Exception {
        mvc.perform(post("/api/v1/auth/password-resets").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "a@example.test", "challengeId", "challenge1",
                                "code", "123456", "newPassword", "NewPassword!2026", "purpose", "REGISTER"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void preservesChallengeErrorRateLimitAndDependencyStatus() throws Exception {
        doThrow(new BusinessException(ErrorCode.CHALLENGE_INVALID))
                .doThrow(new BusinessException(ErrorCode.RATE_LIMITED, "频繁", null, 30L, List.of()))
                .doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE)).when(service).reset(any(), anyString());
        var request = post("/api/v1/auth/password-resets").contentType(MediaType.APPLICATION_JSON).content(body("NewPassword!2026"));
        mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHALLENGE_INVALID"));
        mvc.perform(request).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "30"));
        mvc.perform(request).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    private String body(String password) {
        return json.writeValueAsString(new ResetPasswordRequest(" Creator@Example.test ", "challenge1", "000123", password));
    }
}
