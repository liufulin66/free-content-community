package com.freecontent.community.content.browse.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.dto.PublicContentView;
import com.freecontent.community.content.browse.service.PublicContentService;
import com.freecontent.community.content.browse.service.PublicContentListService;
import com.freecontent.community.content.model.ContentKind;
import com.freecontent.community.user.dto.*;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PublicContentController.class)
@Import(SecurityConfiguration.class)
class PublicContentEndpointTest {
    private static final String TOKEN = "a".repeat(43);
    @Autowired private MockMvc mvc;
    @MockitoBean private PublicContentService service;
    @MockitoBean private PublicContentListService lists;
    @MockitoBean private SessionAuthenticationService authentication;

    @Test void anonymousReadsReturnOnlyThePublicRepresentation() throws Exception {
        when(service.getPublishedContent(eq("content_1"), isNull(), anyString())).thenReturn(content());
        mvc.perform(get("/api/v1/contents/content_1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.publishedRevisionId").value("revision_1"))
                .andExpect(jsonPath("$.data.author.avatarUrl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.viewerHasLiked").value(false))
                .andExpect(jsonPath("$.data.workingRevision").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andExpect(jsonPath("$.data.author.email").doesNotExist());
        verifyNoInteractions(authentication);
    }
    @Test void validBearerIsVerifiedAndPassedToTheUseCase() throws Exception {
        var principal = new SessionPrincipal("session", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)), "digest", 1);
        when(authentication.authenticate(TOKEN)).thenReturn(principal);
        when(service.getPublishedContent(eq("content_1"), eq(principal), anyString())).thenReturn(content());
        mvc.perform(get("/api/v1/contents/content_1").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk());
        verify(authentication).authenticate(TOKEN);
    }
    @ParameterizedTest @ValueSource(strings = {"Bearer bad", "Basic abc", "Bearer undefined", "Bearer null", ""})
    void malformedCredentialsAreNotDowngradedToAnonymous(String header) throws Exception {
        mvc.perform(get("/api/v1/contents/content_1").header("Authorization", header))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings = {"AUTHENTICATION_REQUIRED", "ACCOUNT_RESTRICTED", "EMAIL_VERIFICATION_REQUIRED"})
    void unusableSessionsFollowTheOptionalAuthenticationContract(String code) throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        mvc.perform(get("/api/v1/contents/content_1").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(service);
    }
    @Test void unknownQueryParametersAreRejectedBeforeTheUseCase() throws Exception {
        mvc.perform(get("/api/v1/contents/content_1?draft=true"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(service);
    }
    @Test void creatorMediaPreviewRemainsProtected() throws Exception {
        mvc.perform(get("/api/v1/creator/contents/content_1/revisions/revision_1/media/asset_1/access")).andExpect(status().isUnauthorized());
    }
    private PublicContentView content() {
        Instant time = Instant.parse("2026-11-03T08:00:00Z");
        return new PublicContentView("content_1", ContentKind.ARTICLE, "标题", "正文",
                new PublicUserView("author_1", "作者", "简介", null, time, 1, 0), null, List.of(),
                time, time, "revision_1", 0, 0, false, false, "正文", List.of(), null);
    }
}
