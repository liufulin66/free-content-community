package com.freecontent.community.content.workspace.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.workspace.dto.EditDraftRequest;
import com.freecontent.community.content.workspace.ratelimit.ContentDraftSaveRateLimiter;
import com.freecontent.community.content.workspace.service.impl.ContentDraftSaveServiceImpl;
import com.freecontent.community.content.workspace.service.transaction.ContentDraftSaveTransactionService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.entity.UserAccount;
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
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ContentDraftSaveController.class)
@Import({SecurityConfiguration.class,ContentDraftSaveServiceImpl.class})
class ContentDraftSaveEndpointTest {
    private static final String TOKEN = "a".repeat(43),PATH = "/api/v1/creator/contents/content_1/draft";
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private ContentDraftSaveTransactionService transactions;
    @MockitoBean private ContentDraftSaveRateLimiter limiter;
    @BeforeEach void authenticate() { authenticate(false); }

    @ParameterizedTest @MethodSource("invalidBodies")
    void rejectsInvalidTypesNullsAndBoundsBeforeTransactionsAndQuota(String body,int status) throws Exception {
        mvc.perform(patch(PATH).header("Authorization","Bearer "+TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value(status == 400 ? "MALFORMED_REQUEST" : "VALIDATION_ERROR"));
        verifyNoInteractions(transactions,limiter);
    }
    static Stream<Arguments> invalidBodies() {
        return Stream.of(Arguments.of("{}",422),Arguments.of("{\"expectedVersion\":1}",422),Arguments.of("{\"title\":\"X\"}",422),
                Arguments.of("{\"expectedVersion\":null,\"title\":\"X\"}",422),Arguments.of("{\"expectedVersion\":0,\"title\":\"X\"}",422),
                Arguments.of("{\"expectedVersion\":-1,\"title\":\"X\"}",422),Arguments.of("{\"expectedVersion\":2147483648,\"title\":\"X\"}",422),
                Arguments.of("{\"expectedVersion\":-2147483649,\"title\":\"X\"}",422),Arguments.of("{\"expectedVersion\":9223372036854775808,\"title\":\"X\"}",422),
                Arguments.of("{\"expectedVersion\":\"1\",\"title\":\"X\"}",400),Arguments.of("{\"expectedVersion\":1.0,\"title\":\"X\"}",400),
                Arguments.of("{\"expectedVersion\":true,\"title\":\"X\"}",400),Arguments.of("{\"expectedVersion\":{},\"title\":\"X\"}",400),
                Arguments.of("{\"expectedVersion\":1,\"title\":null}",422),Arguments.of("{\"expectedVersion\":1,\"bodyMarkdown\":null}",422),
                Arguments.of("{\"expectedVersion\":1,\"imageAssetIds\":null}",422),Arguments.of("{\"expectedVersion\":1,\"tagIds\":null}",422),
                Arguments.of("{\"expectedVersion\":1,\"title\":123}",400),Arguments.of("{\"expectedVersion\":1,\"bodyMarkdown\":true}",400),
                Arguments.of("{\"expectedVersion\":1,\"coverAssetId\":123}",400),Arguments.of("{\"expectedVersion\":1,\"videoAssetId\":true}",400),
                Arguments.of("{\"expectedVersion\":1,\"imageAssetIds\":\"a\"}",400),Arguments.of("{\"expectedVersion\":1,\"imageAssetIds\":[1]}",400),
                Arguments.of("{\"expectedVersion\":1,\"tagIds\":[null]}",422),Arguments.of("{\"expectedVersion\":1,\"coverAssetId\":\"bad.id\"}",422),
                Arguments.of("{\"expectedVersion\":1,\"videoAssetId\":\"\"}",422),
                Arguments.of("{\"expectedVersion\":1,\"title\":\""+"😀".repeat(121)+"\"}",422),
                Arguments.of("{\"expectedVersion\":1,\"bodyMarkdown\":\""+"字".repeat(100001)+"\"}",422),
                Arguments.of("{\"expectedVersion\":1,\"imageAssetIds\":["+String.join(",",java.util.stream.IntStream.range(0,51).mapToObj(i -> "\"image_"+i+"\"").toList())+"]}",422),
                Arguments.of("{\"expectedVersion\":1,\"tagIds\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]}",422),
                Arguments.of("{\"expectedVersion\":1,\"kind\":\"VIDEO\"}",400),Arguments.of("{\"expectedVersion\":1,\"authorId\":\"other\"}",400),
                Arguments.of("{\"expectedVersion\":1,\"publicationStatus\":\"PUBLISHED\"}",400),Arguments.of("{\"expectedVersion\":1,\"titleProvided\":false}",400),
                Arguments.of("[]",400),Arguments.of("null",400),Arguments.of("{",400));
    }

    @Test void explicitNullAndEmptyValuesKeepPresenceWhileOmittedFieldsStayAbsent() throws Exception {
        mvc.perform(patch(PATH).header("Authorization","Bearer "+TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"coverAssetId\":null,\"videoAssetId\":null,\"imageAssetIds\":[],\"tagIds\":[],\"title\":\"\"}"))
                .andExpect(status().isOk());
        var captured = ArgumentCaptor.forClass(EditDraftRequest.class); verify(transactions).save(eq("content_1"),captured.capture(),any());
        var input = captured.getValue(); assertTrue(input.hasCoverAssetId()); assertNull(input.getCoverAssetId());
        assertTrue(input.hasVideoAssetId()); assertNull(input.getVideoAssetId()); assertTrue(input.hasTitle()); assertEquals("",input.getTitle());
        assertEquals(List.of(),input.getImageAssetIds()); assertEquals(List.of(),input.getTagIds()); assertFalse(input.hasBodyMarkdown());
    }
    @Test void unicodeBoundariesAndConnectionAddressArePreservedWithoutAnIdempotencyHeader() throws Exception {
        mvc.perform(patch(PATH).header("Authorization","Bearer "+TOKEN).header("X-Forwarded-For","203.0.113.7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2147483647,\"title\":\""+"😀".repeat(120)+"\",\"bodyMarkdown\":\""+"😀".repeat(100000)+"\"}"))
                .andExpect(status().isOk());
        verify(limiter).acquire(anyString(),eq("127.0.0.1"));
    }
    @ParameterizedTest @ValueSource(strings = {"?expectedVersion=99","?status=DRAFT&status=APPROVED"})
    void unknownQueriesStopBeforeBusinessValidationAndCounting(String query) throws Exception {
        mvc.perform(patch(PATH+query).header("Authorization","Bearer "+TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1,\"title\":\"X\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.violations[0].location").value("query"));
        verifyNoInteractions(transactions,limiter);
    }
    @ParameterizedTest @ValueSource(strings = {"bad.id","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void malformedPathsAre422WithoutDatabaseAccess(String id) throws Exception {
        mvc.perform(patch("/api/v1/creator/contents/"+id+"/draft").header("Authorization","Bearer "+TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1,\"title\":\"X\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.violations[0].location").value("path"));
        verifyNoInteractions(transactions,limiter);
    }
    @Test void missingIdentityBlockedWritesAndUnsupportedMediaKeepTheirContractErrors() throws Exception {
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate","Bearer"));
        mvc.perform(patch(PATH).header("Authorization","Bearer "+TOKEN).contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        authenticate(true);
        mvc.perform(patch(PATH).header("Authorization","Bearer "+TOKEN).contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));
        verifyNoInteractions(transactions,limiter);
    }
    private void authenticate(boolean blocked) {
        var account = LoginTestSupport.account(); if (blocked) account.setAccountStatus(UserAccount.Status.WRITE_BLOCKED);
        var user = CurrentUser.from(account,List.of(UserRole.USER));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session",user,TokenDigest.sha256(TOKEN),1L));
    }
}
