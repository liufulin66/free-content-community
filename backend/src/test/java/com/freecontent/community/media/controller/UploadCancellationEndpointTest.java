package com.freecontent.community.media.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.media.service.UploadCancellationService;
import com.freecontent.community.media.service.UploadPolicyService;
import com.freecontent.community.media.service.UploadQueryService;
import com.freecontent.community.media.service.UploadService;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MediaController.class)
@Import(SecurityConfiguration.class)
class UploadCancellationEndpointTest {
    private static final String TOKEN = "a".repeat(43);
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private UploadPolicyService policies;
    @MockitoBean private UploadService uploads;
    @MockitoBean private UploadQueryService queries;
    @MockitoBean private UploadCancellationService cancellations;

    @BeforeEach
    void authenticate() {
        var user = CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER));
        when(authentication.authenticate(TOKEN)).thenReturn(new SessionPrincipal("session", user, TokenDigest.sha256(TOKEN), 1L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "-2147483649", "9223372036854775808", "9999999999999999999999999999999999999999"})
    void integerVersionOutsideTheContractRangeReturns422(String version) throws Exception {
        mvc.perform(delete("/api/v1/media/uploads/upload_1").header("Authorization", "Bearer " + TOKEN)
                        .param("expectedVersion", version))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].location").value("query"))
                .andExpect(jsonPath("$.violations[0].path").value("expectedVersion"));
        verifyNoInteractions(cancellations);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, Integer.MAX_VALUE})
    void versionsAtBothContractBoundariesReachTheBusinessService(int version) throws Exception {
        mvc.perform(delete("/api/v1/media/uploads/upload_1").header("Authorization", "Bearer " + TOKEN)
                        .param("expectedVersion", Integer.toString(version)))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(cancellations).cancel(eq("upload_1"), eq(version), any(), eq("127.0.0.1"));
    }
}
