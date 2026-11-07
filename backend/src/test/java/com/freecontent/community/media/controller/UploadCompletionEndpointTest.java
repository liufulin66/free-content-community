package com.freecontent.community.media.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.media.config.UploadCompletionConfiguration;
import com.freecontent.community.media.service.UploadCompletionService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UploadCompletionController.class)
@Import({SecurityConfiguration.class, UploadCompletionConfiguration.class})
class UploadCompletionEndpointTest {
    private static final String TOKEN = "a".repeat(43);
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private UploadCompletionService service;

    @BeforeEach
    void authenticate() {
        var user = CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session1", user, TokenDigest.sha256(TOKEN), 1L));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejectsInvalidJsonAndFieldValuesBeforeTheBusinessService(String body, int status) throws Exception {
        mvc.perform(post("/api/v1/media/uploads/upload_1/completion")
                        .header("Authorization", "Bearer " + TOKEN).header("Idempotency-Key", "1234567890123456")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value(status == 400 ? "MALFORMED_REQUEST" : "VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("{}", 422), Arguments.of("{\"expectedVersion\":null}", 422),
                Arguments.of("{\"expectedVersion\":0}", 422), Arguments.of("{\"expectedVersion\":-1}", 422),
                Arguments.of("{\"expectedVersion\":\"1\"}", 400), Arguments.of("{\"expectedVersion\":1.0}", 400),
                Arguments.of("{\"expectedVersion\":true}", 400), Arguments.of("{\"expectedVersion\":2147483648}", 400),
                Arguments.of("{\"expectedVersion\":1,\"ownerId\":\"other\"}", 400),
                Arguments.of("{\"expectedVersion\":1,\"partsProvided\":false}", 400),
                Arguments.of("{\"expectedVersion\":1,\"parts\":1}", 400),
                Arguments.of("{\"expectedVersion\":1,\"parts\":[{\"partNumber\":1,\"etag\":123}]}", 400),
                Arguments.of("{\"expectedVersion\":1,\"parts\":[{\"partNumber\":1,\"etag\":\"a\",\"unknown\":1}]}", 400),
                Arguments.of("{\"expectedVersion\":1,\"parts\":[{\"partNumber\":0,\"etag\":\"a\"}]}", 422),
                Arguments.of("{\"expectedVersion\":1,\"parts\":[]}", 422),
                Arguments.of("[]", 400), Arguments.of("null", 400), Arguments.of("{", 400));
    }
}
