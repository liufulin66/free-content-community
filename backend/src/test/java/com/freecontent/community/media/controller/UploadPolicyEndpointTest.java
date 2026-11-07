package com.freecontent.community.media.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.media.config.UploadPolicyConfiguration;
import com.freecontent.community.media.service.impl.UploadPolicyServiceImpl;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MediaController.class)
@Import({SecurityConfiguration.class, UploadPolicyConfiguration.class, UploadPolicyServiceImpl.class})
@TestPropertySource(properties = {
        "app.media.upload-policy.max-image-bytes=2097152",
        "app.media.upload-policy.max-video-bytes=4294967296",
        "app.media.upload-policy.allowed-image-mime-types=image/png",
        "app.media.upload-policy.allowed-video-mime-types=video/webm",
        "app.media.upload-policy.multipart-threshold-bytes=1048576",
        "app.media.upload-policy.max-part-count=32",
        "app.media.upload-policy.upload-url-ttl-seconds=180"})
class UploadPolicyEndpointTest {
    private static final String TOKEN = "a".repeat(43);
    private static final String PATH = "/api/v1/media/upload-policy";
    @Autowired private MockMvc mvc;
    @MockitoBean private SessionAuthenticationService authentication;
    @MockitoBean private com.freecontent.community.media.service.UploadService uploads;
    @MockitoBean private com.freecontent.community.media.service.UploadQueryService queries;
    @MockitoBean private com.freecontent.community.media.service.UploadCancellationService cancellations;

    @Test
    void returnsBoundConfigurationIncludingByteCountsLargerThanAnInteger() throws Exception {
        when(authentication.authenticate(TOKEN)).thenReturn(principal(false));

        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN).header("X-Request-Id", "req_policy_test"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-Id", "req_policy_test"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.requestId").value("req_policy_test"))
                .andExpect(jsonPath("$.serverTime").isNotEmpty())
                .andExpect(jsonPath("$.data.maxImageBytes").value(2097152))
                .andExpect(jsonPath("$.data.maxVideoBytes").value(4294967296L))
                .andExpect(jsonPath("$.data.allowedImageMimeTypes").value(contains("image/png")))
                .andExpect(jsonPath("$.data.allowedVideoMimeTypes").value(contains("video/webm")))
                .andExpect(jsonPath("$.data.multipartThresholdBytes").value(1048576))
                .andExpect(jsonPath("$.data.maxPartCount").value(32))
                .andExpect(jsonPath("$.data.uploadUrlTtlSeconds").value(180));
    }

    @Test
    void missingAndMalformedBearerCannotReadPolicy() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get(PATH).header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(authentication);
    }

    @Test
    void writeBlockedAccountCanReadPolicy() throws Exception {
        when(authentication.authenticate(TOKEN)).thenReturn(principal(true));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxImageBytes").value(2097152));
    }

    @Test
    void disabledAccountCannotReadPolicy() throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.ACCOUNT_RESTRICTED));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void invalidSessionAndUnavailableAuthenticationKeepDifferentErrors() throws Exception {
        when(authentication.authenticate(TOKEN)).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED))
                .thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get(PATH).header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private SessionPrincipal principal(boolean blocked) {
        var account = LoginTestSupport.account();
        if (blocked) account.setAccountStatus(UserAccount.Status.WRITE_BLOCKED);
        return new SessionPrincipal("session1", CurrentUser.from(account, List.of(UserRole.USER)), TokenDigest.sha256(TOKEN), 1L);
    }
}
