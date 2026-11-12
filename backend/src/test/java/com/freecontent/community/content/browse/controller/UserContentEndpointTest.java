package com.freecontent.community.content.browse.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.browse.dto.ContentPageView;
import com.freecontent.community.content.browse.service.UserContentListService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
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

@WebMvcTest(UserContentController.class)
@Import(SecurityConfiguration.class)
class UserContentEndpointTest {
    private static final String TOKEN = "a".repeat(43);
    @Autowired private MockMvc mvc;
    @MockitoBean private UserContentListService service;
    @MockitoBean private SessionAuthenticationService authentication;

    @ParameterizedTest
    @ValueSource(strings = {"draft=true", "kind=ARTICLE", "tagId=tag_1", "limit=1&limit=1", "cursor=x&cursor=y"})
    void undefinedOrRepeatedQueryParametersAre400BeforeTheUseCase(String query) throws Exception {
        mvc.perform(get("/api/v1/users/author_1/contents?" + query))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTHENTICATION_REQUIRED", "ACCOUNT_RESTRICTED", "EMAIL_VERIFICATION_REQUIRED"})
    void unusableCredentialsReturn401WithoutBecomingAnonymous(String code) throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        mvc.perform(get("/api/v1/users/author_1/contents").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer bad", "Basic abc", "Bearer undefined", "Bearer null", ""})
    void malformedAuthorizationIs401BeforeTheUseCase(String value) throws Exception {
        mvc.perform(get("/api/v1/users/author_1/contents").header("Authorization", value))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service, authentication);
    }

    @Test
    void duplicateAuthorizationHeadersAre401() throws Exception {
        mvc.perform(get("/api/v1/users/author_1/contents").header("Authorization", "Bearer " + TOKEN, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service, authentication);
    }

    @Test
    void validIdentityAndRawPageParametersArePassedToTheUseCase() throws Exception {
        var viewer = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)), "digest", 1);
        when(authentication.authenticate(TOKEN)).thenReturn(viewer);
        when(service.listUserContents(eq("author_1"), eq("cursor"), eq("7"), eq(viewer), anyString()))
                .thenReturn(new ContentPageView(List.of(), null, false, Instant.parse("2026-11-11T08:00:00Z")));
        mvc.perform(get("/api/v1/users/author_1/contents?limit=7&cursor=cursor").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        verify(service).listUserContents(eq("author_1"), eq("cursor"), eq("7"), eq(viewer), anyString());
    }

    @Test
    void authenticationDependencyFailureIs503() throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE));
        mvc.perform(get("/api/v1/users/author_1/contents").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        verifyNoInteractions(service);
    }
}
