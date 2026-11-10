package com.freecontent.community.content.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.ratelimit.ContentMediaPreviewRateLimiter;
import com.freecontent.community.content.service.impl.ContentMediaPreviewServiceImpl;
import com.freecontent.community.content.service.transaction.ContentMediaPreviewTransactionService;
import com.freecontent.community.media.dto.MediaAccess;
import com.freecontent.community.media.storage.MediaObjectReader;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.entity.UserAccount;
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

@WebMvcTest(ContentMediaPreviewController.class)
@Import({SecurityConfiguration.class,ContentMediaPreviewServiceImpl.class})
class ContentMediaPreviewEndpointTest {
    private static final String TOKEN = "a".repeat(43),PATH = "/api/v1/creator/contents/content_1/revisions/revision_1/media/asset_1/access";
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private ContentMediaPreviewTransactionService transactions;
    @MockitoBean private ContentMediaPreviewRateLimiter limiter;
    @MockitoBean private MediaObjectReader objects;
    private MediaObjectReader.ObjectReference reference;
    @BeforeEach void authenticate() {
        var user = CurrentUser.from(LoginTestSupport.account(),List.of(UserRole.USER));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session",user,TokenDigest.sha256(TOKEN),1L));
        reference = new MediaObjectReader.ObjectReference("asset_1",user.id(),"key","etag","storage","image/png",68);
        when(transactions.prepare(anyString(),anyString(),anyString(),any())).thenReturn(reference);
        when(transactions.finish(anyString(),anyString(),anyString(),any(),any(),anyString()))
                .thenReturn(new MediaAccess("asset_1","https://storage.example.test/preview",Instant.parse("2026-11-09T12:00:00Z"),"image/png"));
    }
    @ParameterizedTest @MethodSource("invalidPaths")
    void malformedPathIdsStopBeforeQuotaDatabaseAndStorage(String path,String field) throws Exception {
        mvc.perform(get(path).header("Authorization","Bearer "+TOKEN)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.violations[0].location").value("path"))
                .andExpect(jsonPath("$.violations[0].path").value(field));
        verifyNoInteractions(limiter,transactions,objects);
    }
    static Stream<Arguments> invalidPaths() {
        return Stream.of(Arguments.of(PATH.replace("content_1","bad.id"),"contentId"),Arguments.of(PATH.replace("revision_1","bad.id"),"revisionId"),
                Arguments.of(PATH.replace("asset_1","bad.id"),"assetId"),Arguments.of(PATH.replace("content_1","a".repeat(65)),"contentId"),
                Arguments.of(PATH.replace("revision_1","a".repeat(65)),"revisionId"),Arguments.of(PATH.replace("asset_1","a".repeat(65)),"assetId"));
    }
    @ParameterizedTest @ValueSource(strings = {"?ttlSeconds=99999","?url=http://example.test","?x=1&x=2"})
    void unknownQueryParametersCannotSelectTheObjectOrLifetime(String query) throws Exception {
        mvc.perform(get(PATH+query).header("Authorization","Bearer "+TOKEN)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].location").value("query"));
        verifyNoInteractions(limiter,transactions,objects);
    }
    @Test void returnsOnlyMediaAccessAndPropagatesTheRequestIdentityToTheAudit() throws Exception {
        mvc.perform(get(PATH).header("Authorization","Bearer "+TOKEN).header("X-Request-Id","req_preview_test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.assetId").value("asset_1")).andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Request-Id","req_preview_test"));
        verify(limiter).acquire(anyString(),eq("127.0.0.1")); verify(objects).verify(reference);
        verify(transactions).finish(eq("content_1"),eq("revision_1"),eq("asset_1"),any(),eq(reference),eq("req_preview_test"));
    }
    @Test void missingIdentityAndBadBearerStopBeforeBusinessExecution() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate","Bearer"));
        mvc.perform(get(PATH).header("Authorization","Bearer bad-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(limiter,transactions,objects);
    }
}
