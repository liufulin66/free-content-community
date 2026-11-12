package com.freecontent.community.user.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.dto.PublicUserView;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.PublicUserService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PublicUserController.class)
@Import(SecurityConfiguration.class)
class PublicUserEndpointTest {
    private static final String TOKEN = "a".repeat(43);
    @Autowired private MockMvc mvc;
    @MockitoBean private PublicUserService service;
    @MockitoBean private SessionAuthenticationService authentication;

    @ParameterizedTest
    @ValueSource(strings = {"AUTHENTICATION_REQUIRED", "ACCOUNT_RESTRICTED", "EMAIL_VERIFICATION_REQUIRED"})
    void unusableCredentialsReturn401InsteadOfBecomingAnAnonymousRequest(String code) throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        mvc.perform(get("/api/v1/users/author_1").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer bad", "Basic abc", "Bearer undefined", "Bearer null", ""})
    void malformedAuthorizationNeverReachesTheUseCase(String header) throws Exception {
        mvc.perform(get("/api/v1/users/author_1").header("Authorization", header))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(authentication, service);
    }

    @Test
    void duplicateAuthorizationHeadersAreRejected() throws Exception {
        mvc.perform(get("/api/v1/users/author_1").header("Authorization", "Bearer " + TOKEN, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(authentication, service);
    }

    @Test
    void aValidBearerIsVerifiedAndPassedToTheUseCase() throws Exception {
        var viewer = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)), "digest", 1);
        when(authentication.authenticate(TOKEN)).thenReturn(viewer);
        when(service.getPublicUser(eq("author_1"), eq(viewer), anyString())).thenReturn(profile());
        mvc.perform(get("/api/v1/users/author_1").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("author_1"));
        verify(authentication).authenticate(TOKEN);
        verify(service).getPublicUser(eq("author_1"), eq(viewer), anyString());
    }

    @Test
    void authenticationDependencyFailureRemains503() throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE));
        mvc.perform(get("/api/v1/users/author_1").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?draft=true", "?draft=true&draft=false", "?limit=1"})
    void undefinedQueryParametersAre400BeforeTheUseCase(String query) throws Exception {
        mvc.perform(get("/api/v1/users/author_1" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.violations[0].location").value("query"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/me", "/api/v1/users/author_1/contents/private", "/api/v1/admin/users/author_1"})
    void adjacentRoutesRemainProtected(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    private PublicUserView profile() {
        return new PublicUserView("author_1", "作者", "简介", null, Instant.parse("2026-11-03T08:00:00Z"), 1, 0);
    }
}
