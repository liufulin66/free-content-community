package com.freecontent.community.user.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.PasswordChangeService;
import com.freecontent.community.user.service.impl.CurrentUserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MeController.class)
@Import({SecurityConfiguration.class, CurrentUserServiceImpl.class})
class MeCurrentUserEndpointTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private PasswordChangeService passwords;
    @MockitoBean private SessionAuthenticationService authentication;
    private static final String TOKEN = "a".repeat(43);

    @Test
    void returnsContractEnvelopeAndOnlyCurrentUserFields() throws Exception {
        when(authentication.authenticate(TOKEN)).thenReturn(principal(false));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TOKEN).header("X-Request-Id", "req_me_test"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-Id", "req_me_test"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.requestId").value("req_me_test"))
                .andExpect(jsonPath("$.serverTime").isNotEmpty())
                .andExpect(jsonPath("$.data.id").value("user1"))
                .andExpect(jsonPath("$.data.email").value("Creator@Example.test"))
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.displayName").value("创作者"))
                .andExpect(jsonPath("$.data.bio").value(""))
                .andExpect(jsonPath("$.data.avatarAssetId").value(nullValue()))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.canCreate").value(true))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.createdAt").value("2026-11-05T00:00:00Z"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.tokenDigest").doesNotExist())
                .andExpect(jsonPath("$.data.authVersion").doesNotExist())
                .andExpect(jsonPath("$.data.sessionId").doesNotExist());
        verify(authentication, times(1)).authenticate(TOKEN);
        verifyNoInteractions(passwords);
    }

    @Test
    void missingBearerCannotReturnUserData() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(authentication, passwords);
    }

    @Test
    void writeBlockedUserCanReadProfileWithCanCreateFalse() throws Exception {
        when(authentication.authenticate(TOKEN)).thenReturn(principal(true));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountStatus").value("WRITE_BLOCKED"))
                .andExpect(jsonPath("$.data.canCreate").value(false));
    }

    @Test
    void disabledAccountCannotReturnUserData() throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.ACCOUNT_RESTRICTED));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void dependencyFailureAndInvalidTokenKeepDifferentStatuses() throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE))
                .thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
        var request = get("/api/v1/me").header("Authorization", "Bearer " + TOKEN);
        mvc.perform(request).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    private SessionPrincipal principal(boolean blocked) {
        var account = LoginTestSupport.account();
        if (blocked) account.setAccountStatus(UserAccount.Status.WRITE_BLOCKED);
        return new SessionPrincipal("session1", CurrentUser.from(account, List.of(UserRole.USER)), TokenDigest.sha256(TOKEN), 1L);
    }
}
