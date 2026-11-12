package com.freecontent.community.content.review.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.review.ratelimit.AdminReviewMediaPreviewRateLimiter;
import com.freecontent.community.content.review.service.impl.AdminReviewMediaPreviewServiceImpl;
import com.freecontent.community.content.review.service.transaction.AdminReviewMediaPreviewTransactionService;
import com.freecontent.community.media.dto.MediaAccess;
import com.freecontent.community.media.storage.MediaObjectReader;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminReviewMediaPreviewController.class)
@Import({SecurityConfiguration.class, AdminReviewMediaPreviewServiceImpl.class})
class AdminReviewMediaPreviewEndpointTest {
    private static final String TOKEN = "a".repeat(43), PATH = "/api/v1/admin/reviews/review_1/media/asset_1/access";
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private AdminReviewMediaPreviewTransactionService transactions;
    @MockitoBean private AdminReviewMediaPreviewRateLimiter limiter;
    @MockitoBean private MediaObjectReader objects;
    private AdminReviewMediaPreviewTransactionService.Proof proof;
    @BeforeEach void authenticate() {
        var user = CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.MODERATOR));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session", user, TokenDigest.sha256(TOKEN), 1L));
        proof = new AdminReviewMediaPreviewTransactionService.Proof("review_1", "content_1", "revision_1", "author_1",
                new MediaObjectReader.ObjectReference("asset_1", "author_1", "key", "etag", "storage", "image/png", 68));
        when(transactions.prepare(anyString(), anyString(), any())).thenReturn(proof);
        when(transactions.finish(anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(new MediaAccess("asset_1", "https://storage.example.test/preview", Instant.parse("2026-11-10T12:00:00Z"), "image/png"));
    }

    @ParameterizedTest @MethodSource("invalidPaths")
    void malformedPathIdsStopBeforeQuotaAndStorage(String path, String field) throws Exception {
        mvc.perform(get(path).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.violations[0].location").value("path"))
                .andExpect(jsonPath("$.violations[0].path").value(field));
        verifyNoInteractions(limiter, transactions, objects);
    }
    static Stream<Arguments> invalidPaths() {
        return Stream.of(Arguments.of(PATH.replace("review_1", "bad.id"), "reviewId"), Arguments.of(PATH.replace("asset_1", "bad.id"), "assetId"),
                Arguments.of(PATH.replace("review_1", "a".repeat(65)), "reviewId"), Arguments.of(PATH.replace("asset_1", "a".repeat(65)), "assetId"));
    }
    @ParameterizedTest @ValueSource(strings = {"?ttlSeconds=99999", "?url=http://example.test", "?revisionId=other", "?x=1&x=2"})
    void queriesCannotOverrideTheReviewObjectOrSigningLifetime(String query) throws Exception {
        mvc.perform(get(PATH + query).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].location").value("query"));
        verifyNoInteractions(limiter, transactions, objects);
    }
    @Test void returnsMediaAccessAndCarriesTheRequestIdentityToTheFinalAudit() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN).header("X-Request-Id", "req_review_preview")
                        .header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.assetId").value("asset_1"))
                .andExpect(jsonPath("$.data.contentType").value("image/png")).andExpect(header().string("Cache-Control", "no-store"));
        verify(limiter).acquire(anyString(), eq("127.0.0.1")); verify(objects).verify(proof.object());
        verify(transactions).finish(eq("review_1"), eq("asset_1"), any(), eq(proof), eq("req_review_preview"));
    }
    @Test void ordinaryAuthorsAndMissingIdentitiesCannotEnterThePreviewFlow() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        var user = CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session", user, TokenDigest.sha256(TOKEN), 1L));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isForbidden());
        verifyNoInteractions(limiter, transactions, objects);
    }
}
