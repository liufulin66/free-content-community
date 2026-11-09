package com.freecontent.community.tag.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.tag.service.TagCreationService;
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

@WebMvcTest(TagCreationController.class)
@Import(SecurityConfiguration.class)
class TagCreationEndpointTest {
    private static final String TOKEN = "a".repeat(43);
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private TagCreationService service;
    @BeforeEach void authenticate() {
        var user = CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.ADMIN));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session", user, TokenDigest.sha256(TOKEN), 1L));
    }
    @ParameterizedTest @MethodSource("invalidRequests")
    void invalidInputIsRejectedBeforeTheBusinessService(String body, int status) throws Exception {
        mvc.perform(post("/api/v1/admin/tags").header("Authorization", "Bearer " + TOKEN)
                        .header("Idempotency-Key", "1234567890123456").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(status == 400 ? "MALFORMED_REQUEST" : "VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }
    static Stream<Arguments> invalidRequests() {
        return Stream.of(Arguments.of("{}", 422), Arguments.of("{\"name\":null}", 422),
                Arguments.of("{\"name\":\" \"}", 422), Arguments.of("{\"name\":\"" + "😀".repeat(31) + "\"}", 422),
                Arguments.of("{\"name\":1}", 400), Arguments.of("{\"name\":false}", 400),
                Arguments.of("{\"name\":\"Java\",\"description\":null}", 422),
                Arguments.of("{\"name\":\"Java\",\"description\":true}", 400),
                Arguments.of("{\"name\":\"Java\",\"description\":\"" + "😀".repeat(201) + "\"}", 422),
                Arguments.of("{\"name\":\"Java\",\"status\":null}", 422),
                Arguments.of("{\"name\":\"Java\",\"status\":0}", 400),
                Arguments.of("{\"name\":\"Java\",\"status\":\"0\"}", 400),
                Arguments.of("{\"name\":\"Java\",\"status\":\"active\"}", 400),
                Arguments.of("{\"name\":\"Java\",\"version\":1}", 400),
                Arguments.of("{\"name\":\"Java\",\"createdBy\":\"other\"}", 400),
                Arguments.of("[]", 400), Arguments.of("null", 400), Arguments.of("{", 400));
    }
}
