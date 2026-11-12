package com.freecontent.community.content.review.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.review.dto.ReviewPage;
import com.freecontent.community.content.review.ratelimit.AdminReviewQueryRateLimiter;
import com.freecontent.community.content.review.service.impl.AdminReviewQueryServiceImpl;
import com.freecontent.community.content.review.service.transaction.AdminReviewQueryTransactionService;
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
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminReviewQueryController.class)
@Import({SecurityConfiguration.class, AdminReviewQueryServiceImpl.class})
class AdminReviewListEndpointTest {
    private static final String TOKEN = "a".repeat(43), PATH = "/api/v1/admin/reviews";
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private AdminReviewQueryTransactionService transactions;
    @MockitoBean private AdminReviewQueryRateLimiter limiter;
    @BeforeEach void authenticate() {
        var user = CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.MODERATOR));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session", user, TokenDigest.sha256(TOKEN), 1L));
        when(transactions.list(any(), anyInt(), anyString(), nullable(String.class), any()))
                .thenAnswer(call -> new ReviewPage(List.of(), call.getArgument(0), call.getArgument(1), 0));
    }

    @ParameterizedTest @MethodSource("invalidQueries")
    void invalidQueriesStopBeforeQuotaAndDatabase(String query, int status, String field) throws Exception {
        mvc.perform(get(PATH + query).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(status == 400 ? "MALFORMED_REQUEST" : "VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].location").value("query")).andExpect(jsonPath("$.violations[0].path").value(field));
        verifyNoInteractions(transactions, limiter);
    }
    static Stream<Arguments> invalidQueries() {
        return Stream.of(Arguments.of("?page=0",422,"page"), Arguments.of("?page=-1",422,"page"), Arguments.of("?page=",400,"page"),
                Arguments.of("?page=abc",400,"page"), Arguments.of("?page=1.0",400,"page"), Arguments.of("?page=1e3",400,"page"),
                Arguments.of("?pageSize=0",422,"pageSize"), Arguments.of("?pageSize=101",422,"pageSize"), Arguments.of("?pageSize=9223372036854775808",422,"pageSize"),
                Arguments.of("?pageSize=",400,"pageSize"), Arguments.of("?pageSize=true",400,"pageSize"),
                Arguments.of("?state=ALL",400,"state"), Arguments.of("?state=pending",400,"state"), Arguments.of("?state=",400,"state"),
                Arguments.of("?purpose=OTHER",400,"purpose"), Arguments.of("?purpose=update",400,"purpose"), Arguments.of("?purpose=",400,"purpose"),
                Arguments.of("?authorId=other",400,"authorId"), Arguments.of("?sort=id",400,"sort"), Arguments.of("?includeDeleted=true",400,"includeDeleted"),
                Arguments.of("?page=1&page=2",400,"page"), Arguments.of("?pageSize=10&pageSize=20",400,"pageSize"),
                Arguments.of("?state=PENDING&state=REJECTED",400,"state"), Arguments.of("?purpose=INITIAL&purpose=UPDATE",400,"purpose"));
    }

    @Test void defaultsAndEmptyPagesRemainAContractResponse() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(transactions).list(eq(BigInteger.ONE), eq(20), eq("PENDING"), isNull(), any());
    }
    @Test void validEnumsAndUnboundedPositivePageNumbersArePreserved() throws Exception {
        String huge = "9".repeat(80);
        mvc.perform(get(PATH + "?page=" + huge + "&pageSize=100&state=INVALIDATED&purpose=REMEDIATION")
                        .header("Authorization", "Bearer " + TOKEN).header("X-Forwarded-For", "203.0.113.7")).andExpect(status().isOk());
        verify(transactions).list(eq(new BigInteger(huge)), eq(100), eq("INVALIDATED"), eq("REMEDIATION"), any());
        verify(limiter).acquire(anyString(), eq("127.0.0.1"));
    }
    @Test void missingIdentityAndNormalUsersAreRejectedBeforeBusinessExecution() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        var user = CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session", user, TokenDigest.sha256(TOKEN), 1L));
        mvc.perform(get(PATH + "?state=BAD").header("Authorization", "Bearer " + TOKEN)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        verifyNoInteractions(transactions, limiter);
    }
}
