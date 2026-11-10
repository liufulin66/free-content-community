package com.freecontent.community.auth.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.dto.*;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({AuthController.class, SessionEndpointTest.ProtectedController.class})
@Import({SecurityConfiguration.class, SessionEndpointTest.ProtectedController.class})
class SessionEndpointTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private LoginService service;
    @MockitoBean private LogoutService logoutService;
    @MockitoBean private PasswordResetService passwordResetService;
    @MockitoBean private EmailChallengeService emailChallengeService;
    @MockitoBean private RegistrationService registrationService;
    @MockitoBean private SessionAuthenticationService authentication;
    private static final String TOKEN = "a".repeat(43);

    @ParameterizedTest
    @ValueSource(strings = {"123456789012", "12.5", "true", "false"})
    void rejectsNonStringPasswordsBeforeCallingLogin(String password) throws Exception {
        mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.test\",\"password\":" + password + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void loginMatchesContractAndIgnoresStaleBearerOnPublicEntry() throws Exception {
        Instant expires = Instant.parse("2026-11-06T08:00:00Z");
        when(service.login(any(), anyString())).thenReturn(new LoginResult(TOKEN, "Bearer", expires,
                "session1", principal(false).user()));
        mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(json(" Password! ", "浏览器"))
                        .header("Authorization", "Bearer stale-token")
                        .header("X-Forwarded-For", "203.0.113.2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").value(TOKEN))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresAt").value(expires.toString()))
                .andExpect(jsonPath("$.data.sessionId").value("session1"))
                .andExpect(jsonPath("$.data.user.canCreate").value(true))
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.user.authVersion").doesNotExist())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.serverTime").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(service).login(new LoginRequest("Creator@Example.test", " Password! ", "浏览器"), "127.0.0.1");
        verifyNoInteractions(authentication);
    }

    @Test
    void loginAcceptsShortPasswordsAndCountsUnicodeCharacters() throws Exception {
        for (String password : List.of("x", " ", "😀".repeat(128))) {
            mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                            .content(json(password, "😀".repeat(80))))
                    .andExpect(status().isCreated());
        }
        for (String password : List.of("", "😀".repeat(129))) {
            mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                            .content(json(password, "浏览器")))
                    .andExpect(status().isUnprocessableContent());
        }
        mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content(json("x", "😀".repeat(81))))
                .andExpect(status().isUnprocessableContent());
        verify(service, times(3)).login(any(), anyString());
    }

    @Test
    void deviceLabelMayBeOmittedAndUnknownFieldsAreRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.test\",\"password\":\"x\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.test\",\"password\":\"x\",\"roles\":[\"ADMIN\"]}"))
                .andExpect(status().isBadRequest());
        verify(service, times(1)).login(any(), anyString());
    }

    @Test
    void loginErrorsKeepStatusHeadersAndProblemShape() throws Exception {
        when(service.login(any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_FAILED))
                .thenThrow(new BusinessException(ErrorCode.ACCOUNT_RESTRICTED))
                .thenThrow(new BusinessException(ErrorCode.RATE_LIMITED, "频繁", null, 12L, List.of()))
                .thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE));
        var request = post("/api/v1/auth/sessions").contentType(MediaType.APPLICATION_JSON).content(json("x", "设备"));
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        mvc.perform(request).andExpect(status().isForbidden());
        mvc.perform(request).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "12"));
        mvc.perform(request).andExpect(status().isServiceUnavailable());
    }

    @Test
    void missingOrMalformedBearerCannotUseProtectedRoute() throws Exception {
        mvc.perform(get("/api/v1/test/private")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(get("/api/v1/test/private").header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/test/private").header("Authorization", "Bearer " + TOKEN, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/test/private").cookie(new Cookie("session", TOKEN)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(authentication);
    }

    @Test
    void bearerEstablishesIdentityAndIsClearedAfterRequest() throws Exception {
        when(authentication.authenticate(TOKEN)).thenReturn(principal(false));
        mvc.perform(get("/api/v1/test/private").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("user1"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        mvc.perform(get("/api/v1/test/private")).andExpect(status().isUnauthorized());
    }

    @Test
    void writeBlockedUserCanReadButCannotWriteBusinessData() throws Exception {
        when(authentication.authenticate(TOKEN)).thenReturn(principal(true));
        mvc.perform(get("/api/v1/test/private").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canCreate").value(false));
        mvc.perform(post("/api/v1/test/private").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));
    }

    @Test
    void authenticationDependencyFailureIs503AndInvalidSessionIs401() throws Exception {
        when(authentication.authenticate(TOKEN))
                .thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE))
                .thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
        mvc.perform(get("/api/v1/test/private").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        mvc.perform(get("/api/v1/test/private").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /api/v1/me, PERMISSION_DENIED",
            "PATCH, /api/v1/me, PERMISSION_DENIED",
            "PUT, /api/v1/me/password, PERMISSION_DENIED",
            "DELETE, /api/v1/auth/sessions/current, PERMISSION_DENIED",
            "POST, /api/v1/admin/tags, PERMISSION_DENIED",
            "GET, /api/v1/media/upload-policy, PERMISSION_DENIED",
            "GET, /api/v1/media/uploads/upload_1, PERMISSION_DENIED",
            "DELETE, /api/v1/media/uploads/upload_1, PERMISSION_DENIED",
            "POST, /api/v1/media/uploads/upload_1/completion, PERMISSION_DENIED",
            "GET, /api/v1/media/assets/asset_1, PERMISSION_DENIED",
            "GET, /api/v1/tasks/task_1, PERMISSION_DENIED",
            "POST, /api/v1/media/uploads, EMAIL_VERIFICATION_REQUIRED",
            "POST, /api/v1/creator/contents, EMAIL_VERIFICATION_REQUIRED",
            "GET, /api/v1/creator/contents/content_1, PERMISSION_DENIED",
            "PATCH, /api/v1/creator/contents/content_1/draft, PERMISSION_DENIED",
            "GET, /api/v1/creator/contents, PERMISSION_DENIED",
            "GET, /api/v1/creator/contents/content_1/revisions/revision_1/media/asset_1/access, PERMISSION_DENIED"
    })
    void unverifiedAccountIsRejectedWithTheOperationsContractCode(String method, String path, String code) throws Exception {
        when(authentication.authenticate(TOKEN))
                .thenThrow(new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED, "请先完成邮箱验证"));

        mvc.perform(request(HttpMethod.valueOf(method), path).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(header().string("Cache-Control", "no-store"));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(service, logoutService, passwordResetService, emailChallengeService, registrationService);
    }

    @Test
    void logoutReturnsEmpty204AndUsesAuthenticatedSession() throws Exception {
        SessionPrincipal currentSession = principal(false);
        when(authentication.authenticate(TOKEN)).thenReturn(currentSession);
        mvc.perform(delete("/api/v1/auth/sessions/current").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(logoutService).logoutCurrentSession(currentSession);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void logoutWithoutBearerCannotReachService() throws Exception {
        mvc.perform(delete("/api/v1/auth/sessions/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(authentication, logoutService);
    }

    @Test
    void expiredTokenCannotReachLogoutService() throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
        mvc.perform(delete("/api/v1/auth/sessions/current").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(logoutService);
    }

    @Test
    void writeBlockedUserCanLogout() throws Exception {
        SessionPrincipal currentSession = principal(true);
        when(authentication.authenticate(TOKEN)).thenReturn(currentSession);
        mvc.perform(delete("/api/v1/auth/sessions/current").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNoContent());
        verify(logoutService).logoutCurrentSession(currentSession);
    }

    @Test
    void unconfirmedRevocationReturns503InsteadOfSuccess() throws Exception {
        when(authentication.authenticate(TOKEN)).thenReturn(principal(false));
        doThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE)).when(logoutService).logoutCurrentSession(any());
        mvc.perform(delete("/api/v1/auth/sessions/current").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void logoutCorsPreflightAllowsDeleteWithAuthorization() throws Exception {
        mvc.perform(options("/api/v1/auth/sessions/current").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "DELETE")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        verifyNoInteractions(authentication, logoutService);
    }

    @Test
    void corsPreflightWorksBeforeAuthenticationAndRejectsOtherOrigins() throws Exception {
        mvc.perform(options("/api/v1/auth/sessions").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/v1/auth/sessions").header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void corsPreflightAllowsLocalNginxFrontendOrigins() throws Exception {
        for (String origin : List.of("http://localhost:8088", "http://127.0.0.1:8088")) {
            mvc.perform(options("/api/v1/auth/sessions").header("Origin", origin)
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Content-Type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
    }

    private SessionPrincipal principal(boolean blocked) {
        UserAccount account = LoginTestSupport.account();
        if (blocked) account.setAccountStatus(UserAccount.Status.WRITE_BLOCKED);
        return new SessionPrincipal("session1", CurrentUser.from(account, List.of(UserRole.USER)), TokenDigest.sha256(TOKEN), 1L);
    }

    private String json(String password, String device) {
        return "{\"email\":\" Creator@Example.test \",\"password\":\"" + password
                + "\",\"deviceLabel\":\"" + device + "\"}";
    }

    @RestController
    static class ProtectedController {
        @GetMapping("/api/v1/test/private")
        CurrentUser read(@AuthenticationPrincipal SessionPrincipal principal) { return principal.user(); }
        @PostMapping("/api/v1/test/private")
        String write() { return "ok"; }
    }
}
