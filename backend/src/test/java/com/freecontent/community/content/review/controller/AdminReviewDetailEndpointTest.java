package com.freecontent.community.content.review.controller;

import com.freecontent.community.content.review.dto.AdminReviewDetail;
import com.freecontent.community.content.review.dto.ReviewView;
import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.model.ContentKind;
import com.freecontent.community.content.review.ratelimit.AdminReviewQueryRateLimiter;
import com.freecontent.community.content.review.service.impl.AdminReviewQueryServiceImpl;
import com.freecontent.community.content.review.service.transaction.AdminReviewQueryTransactionService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.*;
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

@WebMvcTest(AdminReviewQueryController.class)
@Import({SecurityConfiguration.class, AdminReviewQueryServiceImpl.class})
class AdminReviewDetailEndpointTest {
    private static final String TOKEN = "a".repeat(43), PATH = "/api/v1/admin/reviews/review_1";
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private AdminReviewQueryTransactionService transactions;
    @MockitoBean private AdminReviewQueryRateLimiter limiter;
    @BeforeEach void authenticate() {
        var user = CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.MODERATOR));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session", user, TokenDigest.sha256(TOKEN), 1L));
    }
    @ParameterizedTest @ValueSource(strings = {"bad.id", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "中文"})
    void invalidReviewIdsReturn422BeforeCountingOrReading(String id) throws Exception {
        mvc.perform(get("/api/v1/admin/reviews/" + id).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.violations[0].location").value("path"))
                .andExpect(jsonPath("$.violations[0].path").value("reviewId"));
        verifyNoInteractions(transactions, limiter);
    }
    @ParameterizedTest @ValueSource(strings = {"?page=1", "?state=PENDING", "?purpose=INITIAL", "?expectedVersion=2", "?reviewId=other", "?x=1&x=2"})
    void detailAcceptsNoListFiltersOrOtherQueryParameters(String query) throws Exception {
        mvc.perform(get(PATH + query).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST")).andExpect(jsonPath("$.violations[0].location").value("query"));
        verifyNoInteractions(transactions, limiter);
    }
    @Test void theResponseKeepsTheFrozenRevisionSeparateFromTheCurrentDraft() throws Exception {
        var time = Instant.parse("2026-11-10T00:00:00Z");
        var frozen = new RevisionView("frozen_1", "content_1", 1, "REJECTED", "冻结稿", "正文", null, List.of(), null, List.of(), time, time);
        var current = new RevisionView("draft_2", "content_1", 2, "DRAFT", "当前稿", "正文", null, List.of(), null, List.of(), time, time);
        var review = new ReviewView("review_1", "content_1", "frozen_1", "INITIAL", "REJECTED", 2, time, time, "moderator_1", "反馈");
        var content = new ContentWorkspace("content_1", "author_1", ContentKind.ARTICLE, "ACTIVE", "UNPUBLISHED", 8, current, null, null, time, time, null);
        when(transactions.read(eq("review_1"), any())).thenReturn(new AdminReviewDetail(review, content, frozen));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN).header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.review.version").value(2)).andExpect(jsonPath("$.data.content.version").value(8))
                .andExpect(jsonPath("$.data.revision.id").value("frozen_1")).andExpect(jsonPath("$.data.content.workingRevision.id").value("draft_2"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(limiter).acquire(anyString(), eq("127.0.0.1"));
    }
    @Test void identityAndReviewPermissionAreRequiredBeforeAnyBusinessWork() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        var user = CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session", user, TokenDigest.sha256(TOKEN), 1L));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isForbidden());
        verifyNoInteractions(transactions, limiter);
    }
}
