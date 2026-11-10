package com.freecontent.community.content.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.model.ContentKind;
import com.freecontent.community.content.service.ContentCreationService;
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

@WebMvcTest(ContentCreationController.class)
@Import(SecurityConfiguration.class)
class ContentCreationEndpointTest {
    private static final String TOKEN = "a".repeat(43), PATH = "/api/v1/creator/contents", KEY = "1234567890123456";
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private ContentCreationService service;

    @BeforeEach void authenticate() {
        var user = CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session", user, TokenDigest.sha256(TOKEN), 1L));
        when(service.create(any(), anyString(), any(), anyString())).thenReturn(new ContentWorkspace("content_1", user.id(), ContentKind.ARTICLE,
                "ACTIVE", "UNPUBLISHED", 1, null, null, null, Instant.EPOCH, Instant.EPOCH, null));
    }

    @ParameterizedTest @MethodSource("invalidBodies")
    void rejectsMalformedTypesNullsBoundsAndPrivilegedFieldsBeforeTheBusinessService(String body, int status) throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value(status == 400 ? "MALFORMED_REQUEST" : "VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(Arguments.of("{}", 422), Arguments.of("{\"kind\":null}", 422),
                Arguments.of("{\"kind\":1}", 400), Arguments.of("{\"kind\":\"article\"}", 400),
                Arguments.of("{\"kind\":\"ARTICLE\",\"title\":null}", 422),
                Arguments.of("{\"kind\":\"ARTICLE\",\"bodyMarkdown\":null}", 422),
                Arguments.of("{\"kind\":\"ARTICLE\",\"imageAssetIds\":null}", 422),
                Arguments.of("{\"kind\":\"ARTICLE\",\"tagIds\":null}", 422),
                Arguments.of("{\"kind\":\"ARTICLE\",\"title\":123}", 400),
                Arguments.of("{\"kind\":\"ARTICLE\",\"bodyMarkdown\":true}", 400),
                Arguments.of("{\"kind\":\"ARTICLE\",\"imageAssetIds\":\"a\"}", 400),
                Arguments.of("{\"kind\":\"ARTICLE\",\"imageAssetIds\":[1]}", 400),
                Arguments.of("{\"kind\":\"ARTICLE\",\"tagIds\":[null]}", 422),
                Arguments.of("{\"kind\":\"ARTICLE\",\"coverAssetId\":\"bad.id\"}", 422),
                Arguments.of("{\"kind\":\"ARTICLE\",\"videoAssetId\":\"\"}", 422),
                Arguments.of("{\"kind\":\"ARTICLE\",\"title\":\"" + "😀".repeat(121) + "\"}", 422),
                Arguments.of("{\"kind\":\"ARTICLE\",\"bodyMarkdown\":\"" + "字".repeat(100001) + "\"}", 422),
                Arguments.of("{\"kind\":\"ARTICLE\",\"tagIds\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]}", 422),
                Arguments.of("{\"kind\":\"ARTICLE\",\"authorId\":\"other\"}", 400),
                Arguments.of("{\"kind\":\"ARTICLE\",\"publicationStatus\":\"PUBLISHED\"}", 400),
                Arguments.of("{\"kind\":\"ARTICLE\",\"version\":99}", 400),
                Arguments.of("{\"kind\":\"ARTICLE\",\"titleProvided\":false}", 400),
                Arguments.of("[]", 400), Arguments.of("null", 400), Arguments.of("{", 400));
    }

    @ParameterizedTest @ValueSource(strings = {"?status=PUBLISHED", "?kind=VIDEO&kind=ARTICLE"})
    void refusesUndeclaredQueryParametersBeforeCallingTheBusinessService(String query) throws Exception {
        mvc.perform(post(PATH + query).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"ARTICLE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.violations[0].location").value("query"));
        verifyNoInteractions(service);
    }

    @Test void acceptsUnicodeBoundariesAndUsesTheConnectionAddress() throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY)
                        .header("X-Forwarded-For", "203.0.113.1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"ARTICLE\",\"title\":\"" + "😀".repeat(120) + "\",\"bodyMarkdown\":\"" + "😀".repeat(100000) + "\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", PATH + "/content_1"));
        verify(service).create(any(), eq(KEY), any(), eq("127.0.0.1"));
    }

    @Test void missingIdentityHeaderAndWrongMediaTypeKeepTheirContractErrors() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"ARTICLE\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH).header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", KEY).contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }
}
