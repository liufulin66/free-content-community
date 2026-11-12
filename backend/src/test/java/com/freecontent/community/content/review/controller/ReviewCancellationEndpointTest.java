package com.freecontent.community.content.review.controller;

import com.freecontent.community.content.review.dto.CancelReviewRequest;
import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.content.review.dto.ReviewView;
import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.review.ratelimit.ReviewCancellationRateLimiter;
import com.freecontent.community.content.review.service.impl.ReviewCancellationServiceImpl;
import com.freecontent.community.content.review.service.transaction.ReviewCancellationTransactionService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReviewCancellationController.class)
@Import({SecurityConfiguration.class, ReviewCancellationServiceImpl.class})
class ReviewCancellationEndpointTest {
    private static final String TOKEN = "a".repeat(43), PATH = "/api/v1/creator/contents/content_1/reviews/review_1/cancellation";
    private static final String VALID = "{\"expectedVersion\":2,\"expectedReviewVersion\":1}";
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private ReviewCancellationTransactionService transactions;
    @MockitoBean private ReviewCancellationRateLimiter limiter;
    @BeforeEach void authenticate() {
        var user = CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session", user, TokenDigest.sha256(TOKEN), 1L));
        var time = Instant.parse("2026-11-10T00:00:00Z");
        when(transactions.cancel(anyString(), anyString(), any(), any(), anyString())).thenReturn(new ReviewResult(
                new ReviewView("review_1", "content_1", "revision_1", "INITIAL", "CANCELLED", 2, time, time, null, null), 3));
    }
    @ParameterizedTest @MethodSource("invalidBodies")
    void malformedTypesAndOutOfRangeVersionsStopBeforeQuotaAndTransactions(String body, int status) throws Exception {
        mvc.perform(put(PATH).header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(status == 400 ? "MALFORMED_REQUEST" : "VALIDATION_ERROR"));
        verifyNoInteractions(transactions, limiter);
    }
    static Stream<Arguments> invalidBodies() {
        var cases = new ArrayList<Arguments>();
        for (String raw : List.of("null", "0", "-1", "2147483648", "9223372036854775808")) {
            cases.add(Arguments.of(body(raw,"1"),422)); cases.add(Arguments.of(body("2",raw),422));
        }
        for (String raw : List.of("\"1\"", "1.0", "true", "{}", "[]")) {
            cases.add(Arguments.of(body(raw,"1"),400)); cases.add(Arguments.of(body("2",raw),400));
        }
        cases.add(Arguments.of("{}",422)); cases.add(Arguments.of("{\"expectedVersion\":2}",422)); cases.add(Arguments.of("{\"expectedReviewVersion\":1}",422));
        cases.add(Arguments.of("{\"expectedVersion\":2,\"expectedReviewVersion\":1,\"reason\":\"撤销\"}",400));
        cases.add(Arguments.of("{\"expectedVersion\":2,\"expectedReviewVersion\":1,\"expectedContentVersion\":2}",400));
        cases.add(Arguments.of("[]",400)); cases.add(Arguments.of("null",400)); cases.add(Arguments.of("{",400));
        return cases.stream();
    }
    private static String body(String content, String review) { return "{\"expectedVersion\":" + content + ",\"expectedReviewVersion\":" + review + "}"; }

    @ParameterizedTest @MethodSource("invalidPaths")
    void bothPathIdsAreValidatedBeforeCounting(String path, String field) throws Exception {
        mvc.perform(put(path).header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.violations[0].location").value("path"))
                .andExpect(jsonPath("$.violations[0].path").value(field)); verifyNoInteractions(transactions, limiter);
    }
    static Stream<Arguments> invalidPaths() {
        return Stream.of(Arguments.of(PATH.replace("content_1","bad.id"),"contentId"), Arguments.of(PATH.replace("review_1","bad.id"),"reviewId"),
                Arguments.of(PATH.replace("content_1","a".repeat(65)),"contentId"), Arguments.of(PATH.replace("review_1","a".repeat(65)),"reviewId"));
    }
    @ParameterizedTest @ValueSource(strings = {"?expectedVersion=2", "?reason=x", "?x=1&x=2"})
    void noQueryParametersAreAccepted(String query) throws Exception {
        mvc.perform(put(PATH + query).header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.violations[0].location").value("query")); verifyNoInteractions(transactions, limiter);
    }
    @Test void validBoundsRequireNoIdempotencyHeaderAndPreserveRequestIdentity() throws Exception {
        mvc.perform(put(PATH).header("Authorization", "Bearer " + TOKEN).header("X-Request-Id", "req_review_cancel")
                        .header("X-Forwarded-For", "203.0.113.7").contentType(MediaType.APPLICATION_JSON).content(body("2147483647","2147483647")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.review.state").value("CANCELLED"))
                .andExpect(jsonPath("$.data.contentVersion").value(3)).andExpect(header().string("Cache-Control","no-store"));
        var captured = ArgumentCaptor.forClass(CancelReviewRequest.class);
        verify(transactions).cancel(eq("content_1"),eq("review_1"),captured.capture(),any(),eq("req_review_cancel"));
        assertEquals(Integer.MAX_VALUE, captured.getValue().getExpectedVersion()); assertEquals(Integer.MAX_VALUE, captured.getValue().getExpectedReviewVersion());
        verify(limiter).acquire(anyString(),eq("127.0.0.1"));
    }
    @Test void missingIdentityAndUnsupportedMediaKeepTheirContractErrors() throws Exception {
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate","Bearer"));
        mvc.perform(put(PATH).header("Authorization","Bearer " + TOKEN).contentType(MediaType.TEXT_PLAIN).content(VALID)).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(transactions, limiter);
    }
}
