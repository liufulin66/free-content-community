package com.freecontent.community.content.browse.controller;

import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.dto.ContentPageView;
import com.freecontent.community.content.browse.service.PublicContentService;
import com.freecontent.community.content.browse.service.PublicContentListService;
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
class PublicContentListEndpointTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private PublicContentListService service;
    @MockitoBean private PublicContentService details;
    @MockitoBean private SessionAuthenticationService authentication;
    @Test void visitorsCanReadAnEmptyPageWithAnExplicitNullCursor() throws Exception {
        when(service.listPublishedContents(isNull(), isNull(), isNull(), isNull(), isNull(), anyString()))
                .thenReturn(new ContentPageView(List.of(), null, false, Instant.parse("2026-11-11T08:00:00Z")));
        mvc.perform(get("/api/v1/contents")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        verifyNoInteractions(authentication);
    }
    @ParameterizedTest @ValueSource(strings = {"draft=true", "limit=1&limit=1", "kind=ARTICLE&kind=VIDEO", "cursor=x&cursor=y"})
    void unknownOrRepeatedParametersAreRejectedBeforeTheUseCase(String query) throws Exception {
        mvc.perform(get("/api/v1/contents?" + query)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings = {"AUTHENTICATION_REQUIRED", "ACCOUNT_RESTRICTED", "EMAIL_VERIFICATION_REQUIRED"})
    void unusableCredentialsAreNotDowngradedToAnonymous(String code) throws Exception {
        String token = "a".repeat(43);
        when(authentication.authenticate(token)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        mvc.perform(get("/api/v1/contents").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(service);
    }
}
