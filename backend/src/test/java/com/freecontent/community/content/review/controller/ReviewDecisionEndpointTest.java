package com.freecontent.community.content.review.controller;

import com.freecontent.community.content.review.dto.ReviewDecisionRequest;
import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.content.review.dto.ReviewView;
import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.review.ratelimit.ReviewDecisionRateLimiter;
import com.freecontent.community.content.review.service.impl.ReviewDecisionServiceImpl;
import com.freecontent.community.content.review.service.transaction.ReviewDecisionTransactionService;
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
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReviewDecisionController.class)
@Import({SecurityConfiguration.class, ReviewDecisionServiceImpl.class})
class ReviewDecisionEndpointTest {
    private static final String TOKEN = "a".repeat(43), KEY = "k".repeat(16), PATH = "/api/v1/admin/reviews/review_1/decisions";
    private static final String VALID = body("2", "1", "\"APPROVE\"", "\"审核通过\"");
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private ReviewDecisionTransactionService transactions;
    @MockitoBean private ReviewDecisionRateLimiter limiter;
    @BeforeEach void authenticate() {
        var user = CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.MODERATOR));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session", user, TokenDigest.sha256(TOKEN), 1L));
        when(transactions.claim(anyString(), any(), anyString(), anyString(), any())).thenReturn(new ReviewDecisionTransactionService.Claim(null, null, null));
        var time = Instant.parse("2026-11-10T00:00:00Z");
        when(transactions.finish(any(), any(), anyString())).thenReturn(new ReviewResult(new ReviewView("review_1", "content_1", "revision_1", "INITIAL", "APPROVED", 2, time, time, user.id(), "审核通过"), 3));
    }

    @ParameterizedTest @MethodSource("invalidBodies")
    void typesNullsAndBoundsAreRejectedBeforeQuotaAndTransactions(String body, int status) throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(status == 400 ? "MALFORMED_REQUEST" : "VALIDATION_ERROR"));
        verifyNoInteractions(transactions, limiter);
    }
    static Stream<Arguments> invalidBodies() {
        var cases = new ArrayList<Arguments>();
        for (String raw : List.of("null", "0", "-1", "2147483648", "9223372036854775808")) {
            cases.add(Arguments.of(body(raw,"1","\"APPROVE\"","\"原因\""),422));
            cases.add(Arguments.of(body("2",raw,"\"APPROVE\"","\"原因\""),422));
        }
        for (String raw : List.of("\"1\"", "1.0", "true", "{}", "[]")) {
            cases.add(Arguments.of(body(raw,"1","\"APPROVE\"","\"原因\""),400));
            cases.add(Arguments.of(body("2",raw,"\"APPROVE\"","\"原因\""),400));
        }
        for (String raw : List.of("null", "\"\"", "\"   \"", "\"" + "😀".repeat(1001) + "\"")) cases.add(Arguments.of(body("2","1","\"APPROVE\"",raw),422));
        for (String raw : List.of("1", "true", "[]", "{}")) cases.add(Arguments.of(body("2","1","\"APPROVE\"",raw),400));
        for (String raw : List.of("\"APPROVED\"", "\"approve\"", "1", "false")) cases.add(Arguments.of(body("2","1",raw,"\"原因\""),400));
        cases.add(Arguments.of(body("2","1","null","\"原因\""),422));
        cases.add(Arguments.of("{}",422)); cases.add(Arguments.of(VALID.replace("\"expectedContentVersion\":2,",""),422));
        cases.add(Arguments.of(VALID.replace("\"expectedReviewVersion\":1,",""),422));
        cases.add(Arguments.of(VALID.substring(0,VALID.length()-1) + ",\"purpose\":\"REMEDIATION\"}",400));
        cases.add(Arguments.of("[]",400)); cases.add(Arguments.of("null",400)); cases.add(Arguments.of("{",400));
        return cases.stream();
    }
    private static String body(String content, String review, String decision, String reason) {
        return "{\"expectedContentVersion\":" + content + ",\"expectedReviewVersion\":" + review + ",\"decision\":" + decision + ",\"reason\":" + reason + "}";
    }

    @ParameterizedTest @ValueSource(strings = {"bad.id", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidReviewIdsAre422BeforeQuota(String id) throws Exception {
        mvc.perform(post("/api/v1/admin/reviews/" + id + "/decisions").header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.violations[0].location").value("path"));
        verifyNoInteractions(transactions, limiter);
    }
    @ParameterizedTest @ValueSource(strings = {"?decision=REJECT", "?expectedVersion=2", "?x=1&x=2"})
    void queryParametersAreNotAccepted(String query) throws Exception {
        mvc.perform(post(PATH + query).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.violations[0].location").value("query"));
        verifyNoInteractions(transactions, limiter);
    }
    @ParameterizedTest @ValueSource(ints = {0, 15, 129})
    void invalidIdempotencyKeyBoundsDoNotClaimRecords(int length) throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", "k".repeat(length)).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnprocessableContent()); verifyNoInteractions(transactions, limiter);
    }
    @Test void repeatedIdempotencyHeadersAre400() throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY, "b".repeat(16)).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest()); verifyNoInteractions(transactions, limiter);
    }
    @Test void validIntegerBoundsUnicodeReasonAndRequestIdentityReachTheTransaction() throws Exception {
        String reason = "😀".repeat(1000);
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).header("X-Request-Id", "req_decision_test")
                        .contentType(MediaType.APPLICATION_JSON).content(body("2147483647","2147483647","\"APPROVE\"","\"" + reason + "\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.contentVersion").value(3)).andExpect(header().string("Cache-Control", "no-store"));
        var captured = ArgumentCaptor.forClass(ReviewDecisionRequest.class); verify(transactions).claim(eq("review_1"), captured.capture(), anyString(), anyString(), any());
        assertEquals(Integer.MAX_VALUE, captured.getValue().getExpectedContentVersion()); assertEquals(Integer.MAX_VALUE, captured.getValue().getExpectedReviewVersion());
        assertEquals(reason, captured.getValue().getReason()); verify(transactions).finish(any(), any(), eq("req_decision_test")); verify(limiter).acquire(anyString(), eq("127.0.0.1"));
    }
    @Test void missingHeaderIdentityWrongRoleAndUnsupportedContentTypeKeepTheirContractErrors() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType(MediaType.TEXT_PLAIN).content(VALID)).andExpect(status().isUnsupportedMediaType());
        var user = CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session", user, TokenDigest.sha256(TOKEN), 1L));
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isForbidden());
        verifyNoInteractions(transactions, limiter);
    }
}
