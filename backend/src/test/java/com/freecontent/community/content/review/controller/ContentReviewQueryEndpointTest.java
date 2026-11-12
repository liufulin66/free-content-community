package com.freecontent.community.content.review.controller;

import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.content.review.dto.ReviewView;
import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.ratelimit.ContentQueryRateLimiter;
import com.freecontent.community.content.review.service.impl.ContentReviewQueryServiceImpl;
import com.freecontent.community.content.review.service.transaction.ContentReviewQueryTransactionService;
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

@WebMvcTest(ContentReviewQueryController.class)
@Import({SecurityConfiguration.class, ContentReviewQueryServiceImpl.class})
class ContentReviewQueryEndpointTest {
    private static final String TOKEN = "a".repeat(43), PATH = "/api/v1/creator/contents/content_1/reviews/review_1";
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private ContentReviewQueryTransactionService transactions;
    @MockitoBean private ContentQueryRateLimiter limiter;

    @BeforeEach void authenticate() {
        var user = CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session", user, TokenDigest.sha256(TOKEN), 1L));
    }

    @ParameterizedTest @MethodSource("invalidIds")
    void invalidPathIdsAre422BeforeCountingOrReading(String content, String review, String field) throws Exception {
        mvc.perform(get("/api/v1/creator/contents/" + content + "/reviews/" + review).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].location").value("path")).andExpect(jsonPath("$.violations[0].path").value(field));
        verifyNoInteractions(transactions, limiter);
    }
    static Stream<Arguments> invalidIds() {
        return Stream.of(Arguments.of("bad.id", "review_1", "contentId"), Arguments.of("content_1", "bad.id", "reviewId"),
                Arguments.of("a".repeat(65), "review_1", "contentId"), Arguments.of("content_1", "a".repeat(65), "reviewId"),
                Arguments.of("中文", "review_1", "contentId"), Arguments.of("content_1", "中文", "reviewId"));
    }
    @ParameterizedTest @ValueSource(strings = {"?state=PENDING", "?expectedVersion=2", "?includeDeleted=true", "?x=1&x=2", "?reviewId=other"})
    void unknownAndRepeatedQueryParametersAre400BeforeCountingOrReading(String query) throws Exception {
        mvc.perform(get(PATH + query).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST")).andExpect(jsonPath("$.violations[0].location").value("query"));
        verifyNoInteractions(transactions, limiter);
    }
    @Test void successNeedsNoIdempotencyHeaderAndUsesTheResolvedConnectionAddress() throws Exception {
        var review = new ReviewView("review_1", "content_1", "revision_1", "INITIAL", "PENDING", 1, Instant.parse("2026-11-10T00:00:00Z"), null, null, null);
        when(transactions.read(eq("content_1"), eq("review_1"), any())).thenReturn(new ReviewResult(review, 2));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN).header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.contentVersion").value(2))
                .andExpect(jsonPath("$.data.review.id").value("review_1")).andExpect(jsonPath("$.data.review.reason").value(org.hamcrest.Matchers.nullValue()));
        verify(limiter).acquire(anyString(), eq("127.0.0.1"));
    }
    @Test void unauthenticatedRequestsNeverReachCountingOrReading() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        verifyNoInteractions(transactions, limiter);
    }
}
