package com.freecontent.community.content.review.controller;

import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.content.review.dto.ReviewView;
import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.review.ratelimit.ContentReviewSubmissionRateLimiter;
import com.freecontent.community.content.review.service.impl.ContentReviewSubmissionServiceImpl;
import com.freecontent.community.content.review.service.transaction.ContentReviewSubmissionTransactionService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ContentReviewSubmissionController.class)
@Import({SecurityConfiguration.class, ContentReviewSubmissionServiceImpl.class})
class ContentReviewSubmissionEndpointTest {
    private static final String TOKEN = "a".repeat(43), KEY = "k".repeat(16), PATH = "/api/v1/creator/contents/content_1/reviews";
    private static final String VALID = "{\"expectedVersion\":1,\"revisionId\":\"revision_1\"}";
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private ContentReviewSubmissionTransactionService transactions;
    @MockitoBean private ContentReviewSubmissionRateLimiter limiter;
    @BeforeEach void authenticate() {
        var account = LoginTestSupport.account();
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session", CurrentUser.from(account, List.of(UserRole.USER)), TokenDigest.sha256(TOKEN), 1L));
    }

    @ParameterizedTest @MethodSource("invalidBodies")
    void malformedTypesAndOutOfRangeValuesNeverReachDatabaseOrQuota(String body, int status) throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(status == 400 ? "MALFORMED_REQUEST" : "VALIDATION_ERROR"));
        verifyNoInteractions(transactions, limiter);
    }
    static Stream<Arguments> invalidBodies() {
        return Stream.of(Arguments.of("{}",422), Arguments.of("{\"expectedVersion\":1}",422), Arguments.of("{\"revisionId\":\"r\"}",422),
                Arguments.of("{\"expectedVersion\":null,\"revisionId\":\"r\"}",422), Arguments.of("{\"expectedVersion\":0,\"revisionId\":\"r\"}",422),
                Arguments.of("{\"expectedVersion\":-1,\"revisionId\":\"r\"}",422), Arguments.of("{\"expectedVersion\":2147483648,\"revisionId\":\"r\"}",422),
                Arguments.of("{\"expectedVersion\":9223372036854775808,\"revisionId\":\"r\"}",422),
                Arguments.of("{\"expectedVersion\":\"1\",\"revisionId\":\"r\"}",400), Arguments.of("{\"expectedVersion\":1.0,\"revisionId\":\"r\"}",400),
                Arguments.of("{\"expectedVersion\":true,\"revisionId\":\"r\"}",400), Arguments.of("{\"expectedVersion\":{},\"revisionId\":\"r\"}",400),
                Arguments.of("{\"expectedVersion\":1,\"revisionId\":null}",422), Arguments.of("{\"expectedVersion\":1,\"revisionId\":\"\"}",422),
                Arguments.of("{\"expectedVersion\":1,\"revisionId\":\"bad.id\"}",422), Arguments.of("{\"expectedVersion\":1,\"revisionId\":\"" + "a".repeat(65) + "\"}",422),
                Arguments.of("{\"expectedVersion\":1,\"revisionId\":1}",400), Arguments.of("{\"expectedVersion\":1,\"revisionId\":false}",400),
                Arguments.of("{\"expectedVersion\":1,\"revisionId\":[]}",400), Arguments.of("{\"expectedVersion\":1,\"revisionId\":\"r\",\"purpose\":\"INITIAL\"}",400),
                Arguments.of("{\"expectedVersion\":1,\"revisionId\":\"r\",\"authorId\":\"other\"}",400), Arguments.of("[]",400), Arguments.of("null",400), Arguments.of("{",400));
    }
    @ParameterizedTest @ValueSource(strings = {"?expectedVersion=2", "?revisionId=x&revisionId=y"})
    void unknownQueriesNeverReachDatabaseOrQuota(String query) throws Exception {
        mvc.perform(post(PATH + query).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.violations[0].location").value("query"));
        verifyNoInteractions(transactions, limiter);
    }
    @ParameterizedTest @ValueSource(strings = {"bad.id", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidPathIdsAre422(String content) throws Exception {
        mvc.perform(post("/api/v1/creator/contents/" + content + "/reviews").header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.violations[0].location").value("path"));
        verifyNoInteractions(transactions, limiter);
    }
    @Test void repeatedIdempotencyHeadersAreRejectedBeforeClaiming() throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY, "b".repeat(16)).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(transactions, limiter);
    }
    @ParameterizedTest @ValueSource(ints = {0, 15, 129})
    void outOfBoundsIdempotencyKeysAre422(int length) throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", "k".repeat(length)).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnprocessableContent()); verifyNoInteractions(transactions, limiter);
    }
    @Test void identityMediaTypeAndRequiredHeaderKeepTheirContractErrors() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType(MediaType.TEXT_PLAIN).content(VALID)).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(transactions, limiter);
    }
    @Test void successfulResponseIncludesTheCompleteReviewAndResourceLocation() throws Exception {
        var review = new ReviewView("review_1", "content_1", "revision_1", "INITIAL", "PENDING", 1, Instant.parse("2026-11-10T00:00:00Z"), null, null, null);
        when(transactions.claim(anyString(), any(), anyString(), anyString(), any())).thenReturn(new ContentReviewSubmissionTransactionService.Claim(null, null, new ReviewResult(review, 2)));
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated()).andExpect(header().string("Location", PATH + "/review_1"))
                .andExpect(jsonPath("$.data.contentVersion").value(2)).andExpect(jsonPath("$.data.review.id").value("review_1"))
                .andExpect(jsonPath("$.data.review.decidedAt").value(org.hamcrest.Matchers.nullValue()));
        verifyNoInteractions(limiter);
    }
}
