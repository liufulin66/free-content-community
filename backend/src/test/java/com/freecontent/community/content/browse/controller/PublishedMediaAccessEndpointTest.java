package com.freecontent.community.content.browse.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.browse.service.PublishedMediaAccessService;
import com.freecontent.community.media.dto.MediaAccess;
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

@WebMvcTest(PublishedMediaAccessController.class)
@Import(SecurityConfiguration.class)
class PublishedMediaAccessEndpointTest {
    private static final String PATH = "/api/v1/contents/content_1/media/asset_1/access";
    private static final String TOKEN = "a".repeat(43);
    @Autowired private MockMvc mvc;
    @MockitoBean private PublishedMediaAccessService service;
    @MockitoBean private SessionAuthenticationService authentication;

    @ParameterizedTest
    @ValueSource(strings = {"?revisionId=revision_1", "?download=true", "?assetId=other", "?cursor=x&cursor=y"})
    void undefinedQueryParametersAre400BeforeTheUseCase(String query) throws Exception {
        mvc.perform(get(PATH + query)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTHENTICATION_REQUIRED", "ACCOUNT_RESTRICTED", "EMAIL_VERIFICATION_REQUIRED"})
    void unusableCredentialsAre401WithoutAnAnonymousFallback(String code) throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer bad", "Basic abc", "Bearer null", "Bearer undefined", ""})
    void malformedCredentialsAre401BeforeAuthenticationOrSigning(String header) throws Exception {
        mvc.perform(get(PATH).header("Authorization", header)).andExpect(status().isUnauthorized());
        verifyNoInteractions(authentication, service);
    }

    @Test
    void duplicateAuthorizationHeadersAre401() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN, "Bearer " + TOKEN)).andExpect(status().isUnauthorized());
        verifyNoInteractions(authentication, service);
    }

    @Test
    void aVerifiedViewerIsPassedToTheUseCaseAndTheResponseIsNoStore() throws Exception {
        var viewer = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)), "digest", 1);
        when(authentication.authenticate(TOKEN)).thenReturn(viewer);
        when(service.getAccess(eq("content_1"), eq("asset_1"), eq(viewer), anyString()))
                .thenReturn(new MediaAccess("asset_1", "https://storage.example.test/read", Instant.parse("2026-11-11T08:05:00Z"), "image/png"));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.assetId").value("asset_1"))
                .andExpect(jsonPath("$.data.contentType").value("image/png"));
        verify(service).getAccess(eq("content_1"), eq("asset_1"), eq(viewer), anyString());
    }

    @Test
    void authenticationDependencyFailureIs503() throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        verifyNoInteractions(service);
    }
}
