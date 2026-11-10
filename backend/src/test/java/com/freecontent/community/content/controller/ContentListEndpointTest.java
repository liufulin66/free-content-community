package com.freecontent.community.content.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.dto.WorkspacePage;
import com.freecontent.community.content.ratelimit.ContentQueryRateLimiter;
import com.freecontent.community.content.service.impl.ContentWorkspaceServiceImpl;
import com.freecontent.community.content.service.transaction.ContentWorkspaceTransactionService;
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

@WebMvcTest(ContentWorkspaceController.class)
@Import({SecurityConfiguration.class,ContentWorkspaceServiceImpl.class})
class ContentListEndpointTest {
    private static final String PATH = "/api/v1/creator/contents",TOKEN = "a".repeat(43);
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private ContentWorkspaceTransactionService transactions;
    @MockitoBean private ContentQueryRateLimiter limiter;
    @BeforeEach void authenticate() {
        var user = CurrentUser.from(LoginTestSupport.account(),List.of(UserRole.USER));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session",user,TokenDigest.sha256(TOKEN),1L));
        when(transactions.list(any(),anyInt(),anyString(),nullable(String.class),nullable(String.class),any()))
                .thenAnswer(invocation -> new WorkspacePage(List.of(),invocation.getArgument(0),invocation.getArgument(1),0));
    }

    @ParameterizedTest @MethodSource("invalidQueries")
    void rejectsUnknownDuplicateAndInvalidParametersBeforeQuotaOrDatabase(String query,int status,String field) throws Exception {
        mvc.perform(get(PATH+query).header("Authorization","Bearer "+TOKEN))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value(status == 400 ? "MALFORMED_REQUEST" : "VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].location").value("query")).andExpect(jsonPath("$.violations[0].path").value(field));
        verifyNoInteractions(transactions,limiter);
    }
    static Stream<Arguments> invalidQueries() {
        return Stream.of(Arguments.of("?page=0",422,"page"),Arguments.of("?page=-1",422,"page"),Arguments.of("?page=",400,"page"),
                Arguments.of("?page=abc",400,"page"),Arguments.of("?page=1.0",400,"page"),Arguments.of("?page=1e3",400,"page"),
                Arguments.of("?pageSize=0",422,"pageSize"),Arguments.of("?pageSize=101",422,"pageSize"),Arguments.of("?pageSize=9223372036854775808",422,"pageSize"),
                Arguments.of("?pageSize=",400,"pageSize"),Arguments.of("?pageSize=true",400,"pageSize"),
                Arguments.of("?lifecycle=ALL",400,"lifecycle"),Arguments.of("?lifecycle=active",400,"lifecycle"),Arguments.of("?lifecycle=",400,"lifecycle"),
                Arguments.of("?publicationStatus=DRAFT",400,"publicationStatus"),Arguments.of("?publicationStatus=published",400,"publicationStatus"),Arguments.of("?publicationStatus=",400,"publicationStatus"),
                Arguments.of("?kind=TEXT",400,"kind"),Arguments.of("?kind=article",400,"kind"),Arguments.of("?kind=",400,"kind"),
                Arguments.of("?authorId=other",400,"authorId"),Arguments.of("?sort=id",400,"sort"),Arguments.of("?includeDeleted=true",400,"includeDeleted"),
                Arguments.of("?page=1&page=2",400,"page"),Arguments.of("?pageSize=10&pageSize=20",400,"pageSize"),
                Arguments.of("?lifecycle=ACTIVE&lifecycle=DELETED",400,"lifecycle"),Arguments.of("?kind=ARTICLE&kind=VIDEO",400,"kind"),
                Arguments.of("?publicationStatus=PUBLISHED&publicationStatus=WITHDRAWN",400,"publicationStatus"));
    }

    @Test void defaultsAndEmptyResultsRemainAContractPage() throws Exception {
        mvc.perform(get(PATH).header("Authorization","Bearer "+TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20)).andExpect(jsonPath("$.data.total").value(0))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().exists("X-Request-Id"));
        verify(transactions).list(eq(BigInteger.ONE),eq(20),eq("ACTIVE"),isNull(),isNull(),any());
    }
    @Test void validFiltersAndHugePagesArePassedWithoutAddingAnUpperBound() throws Exception {
        String huge = "9".repeat(80);
        mvc.perform(get(PATH+"?page="+huge+"&pageSize=100&lifecycle=DELETED&publicationStatus=TAKEN_DOWN&kind=VIDEO").header("Authorization","Bearer "+TOKEN))
                .andExpect(status().isOk());
        verify(transactions).list(eq(new BigInteger(huge)),eq(100),eq("DELETED"),eq("TAKEN_DOWN"),eq("VIDEO"),any());
    }
    @Test void missingAuthenticationStopsBeforeBusinessExecution() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate","Bearer"));
        verifyNoInteractions(transactions,limiter);
    }
}
