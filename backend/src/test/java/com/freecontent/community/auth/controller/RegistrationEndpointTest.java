package com.freecontent.community.auth.controller;

import com.freecontent.community.auth.dto.RegisterRequest;
import com.freecontent.community.auth.service.RegistrationService;
import com.freecontent.community.auth.service.EmailChallengeService;
import com.freecontent.community.auth.service.LoginService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(SecurityConfiguration.class)
class RegistrationEndpointTest {
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private EmailChallengeService emailChallengeService;
    @MockitoBean private LoginService loginService;
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private RegistrationService service;

    @Test
    void createsVerifiedUserWithoutCredentialsOrSession() throws Exception {
        when(service.register(any(), anyString())).thenReturn(user());
        mvc.perform(post("/api/v1/auth/registrations").contentType(MediaType.APPLICATION_JSON)
                        .content(json(" RegistrationPassword! ", "创作者"))
                        .header("X-Forwarded-For", "203.0.113.2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"))
                .andExpect(jsonPath("$.data.canCreate").value(true))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.avatarAssetId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(service).register(eq(new RegisterRequest("Creator@Example.test", " RegistrationPassword! ",
                "challenge1", "123456", "创作者")), eq("127.0.0.1"));
    }

    @Test
    void validatesUnicodeByCodePointsAndSupportsMaximumLengthPassword() throws Exception {
        when(service.register(any(), anyString())).thenReturn(user());
        mvc.perform(post("/api/v1/auth/registrations").contentType(MediaType.APPLICATION_JSON)
                        .content(json("😀".repeat(128), "😀".repeat(40))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/registrations").contentType(MediaType.APPLICATION_JSON)
                        .content(json("😀".repeat(129), "创作者")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/v1/auth/registrations").contentType(MediaType.APPLICATION_JSON)
                        .content(json("short", "创作者")))
                .andExpect(status().isUnprocessableContent());
        verify(service, times(1)).register(any(), anyString());
    }

    @Test
    void rejectsSelfAssignedRole() throws Exception {
        String body = json("RegistrationPassword!", "创作者");
        body = body.substring(0, body.length() - 1) + ",\"roles\":[\"ADMIN\"]}";
        mvc.perform(post("/api/v1/auth/registrations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void duplicateEmailMatchesConflictContract() throws Exception {
        when(service.register(any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.REGISTRATION_NOT_ALLOWED));
        mvc.perform(post("/api/v1/auth/registrations").contentType(MediaType.APPLICATION_JSON)
                        .content(json("RegistrationPassword!", "创作者")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGISTRATION_NOT_ALLOWED"));
    }

    private String json(String password, String displayName) {
        return "{\"email\":\" Creator@Example.test \",\"password\":\"" + password
                + "\",\"challengeId\":\"challenge1\",\"code\":\"123456\",\"displayName\":\"" + displayName + "\"}";
    }

    private CurrentUser user() {
        return new CurrentUser("user1", "Creator@Example.test", true, "创作者", "", null,
                List.of(UserRole.USER), UserAccount.Status.ACTIVE, true, 1, Instant.now());
    }
}
