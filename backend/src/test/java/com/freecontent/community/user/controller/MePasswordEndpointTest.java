package com.freecontent.community.user.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.user.dto.ChangePasswordRequest;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.user.service.PasswordChangeService;
import com.freecontent.community.user.service.CurrentUserService;
import com.freecontent.community.user.service.UserProfileService;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MeController.class)
@Import(SecurityConfiguration.class)
class MePasswordEndpointTest {
    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @MockitoBean private PasswordChangeService service;
    @MockitoBean private CurrentUserService currentUsers;
    @MockitoBean private UserProfileService profiles;
    @MockitoBean private SessionAuthenticationService authentication;
    private static final String TOKEN = "a".repeat(43);

    @Test
    void usesAuthenticatedAccountAndPreservesPasswordWhitespace() throws Exception {
        SessionPrincipal principal = principal(false);
        when(authentication.authenticate(TOKEN)).thenReturn(principal);
        mvc.perform(put("/api/v1/me/password").header("Authorization", "Bearer " + TOKEN)
                        .header("X-Forwarded-For", "203.0.113.1").contentType(MediaType.APPLICATION_JSON)
                        .content(body(" OldPassword! ", " NewPassword! ")))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(service).change(new ChangePasswordRequest(" OldPassword! ", " NewPassword! "), principal, "127.0.0.1");
    }

    @Test
    void missingBearerCannotReachService() throws Exception {
        mvc.perform(put("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON).content(body("old", "NewPassword!2026")))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(service);
    }

    @Test
    void currentPasswordAndNewPasswordHaveDifferentUnicodeLengthRules() throws Exception {
        when(authentication.authenticate(TOKEN)).thenReturn(principal(false));
        for (String current : List.of("x", "😀".repeat(128))) {
            mvc.perform(put("/api/v1/me/password").header("Authorization", "Bearer " + TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content(body(current, "😀".repeat(128))))
                    .andExpect(status().isNoContent());
        }
        for (ChangePasswordRequest request : List.of(new ChangePasswordRequest("", "NewPassword!2026"),
                new ChangePasswordRequest("😀".repeat(129), "NewPassword!2026"),
                new ChangePasswordRequest("old", "😀".repeat(11)), new ChangePasswordRequest("old", "😀".repeat(129)))) {
            mvc.perform(put("/api/v1/me/password").header("Authorization", "Bearer " + TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableContent());
        }
        verify(service, times(2)).change(any(), any(), anyString());
    }

    @Test
    void rejectsClientSpecifiedUserOrConfirmPassword() throws Exception {
        when(authentication.authenticate(TOKEN)).thenReturn(principal(false));
        for (String field : List.of("userId", "confirmPassword")) {
            Map<String, String> body = new HashMap<>(Map.of("currentPassword", "old", "newPassword", "NewPassword!2026"));
            body.put(field, "other-user");
            mvc.perform(put("/api/v1/me/password").header("Authorization", "Bearer " + TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void wrongPasswordRateLimitAndDependencyErrorsKeepTheirCodes() throws Exception {
        when(authentication.authenticate(TOKEN)).thenReturn(principal(false));
        doThrow(new BusinessException(ErrorCode.AUTHENTICATION_FAILED))
                .doThrow(new BusinessException(ErrorCode.RATE_LIMITED, "频繁", null, 20L, List.of()))
                .doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE)).when(service).change(any(), any(), anyString());
        var request = put("/api/v1/me/password").header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body("old", "NewPassword!2026"));
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        mvc.perform(request).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "20"));
        mvc.perform(request).andExpect(status().isServiceUnavailable());
    }

    @Test
    void writeBlockedUserCanChangePassword() throws Exception {
        when(authentication.authenticate(TOKEN)).thenReturn(principal(true));
        mvc.perform(put("/api/v1/me/password").header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body("old", "NewPassword!2026")))
                .andExpect(status().isNoContent());
    }

    private String body(String current, String next) { return json.writeValueAsString(new ChangePasswordRequest(current, next)); }

    private SessionPrincipal principal(boolean blocked) {
        var account = LoginTestSupport.account();
        if (blocked) account.setAccountStatus(UserAccount.Status.WRITE_BLOCKED);
        return new SessionPrincipal("session1", CurrentUser.from(account, List.of(UserRole.USER)), TokenDigest.sha256(TOKEN), 1L);
    }
}
