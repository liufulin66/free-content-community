package com.freecontent.community.auth.controller;

import com.freecontent.community.auth.dto.ChallengeReceipt;
import com.freecontent.community.auth.dto.CreateEmailChallengeRequest;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.auth.service.EmailChallengeService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EmailChallengeController.class)
class EmailChallengeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EmailChallengeService service;

    @Test
    void acceptedResponseHasReceiptAndNoVerificationCode() throws Exception {
        when(service.create(any(), anyString())).thenReturn(new ChallengeReceipt("challenge1",
                Instant.parse("2026-11-05T00:10:00Z"), Instant.parse("2026-11-05T00:01:00Z")));
        mvc.perform(post("/api/v1/auth/email-challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"REGISTER\",\"email\":\" Creator@Example.test \"}")
                        .with(request -> { request.setRemoteAddr("192.0.2.10"); return request; })
                        .header("X-Forwarded-For", "203.0.113.99"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.challengeId").value("challenge1"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-11-05T00:10:00Z"))
                .andExpect(jsonPath("$.data.code").doesNotExist())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(service).create(eq(new CreateEmailChallengeRequest(
                EmailChallengePurpose.REGISTER, "Creator@Example.test")), eq("192.0.2.10"));
    }

    @Test
    void rejectsUnicodeEmailAndMissingPurposeBeforeCallingService() throws Exception {
        for (String json : List.of(
                "{\"purpose\":\"REGISTER\",\"email\":\"用户@example.test\"}",
                "{\"email\":\"a@example.test\"}")) {
            mvc.perform(post("/api/v1/auth/email-challenges").contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void rejectsUnexpectedFields() throws Exception {
        mvc.perform(post("/api/v1/auth/email-challenges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"REGISTER\",\"email\":\"a@example.test\",\"roles\":[\"ADMIN\"]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void preservesRetryAfterAndDependencyFailure() throws Exception {
        when(service.create(any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.RATE_LIMITED, "请求过于频繁",
                        null, 60L, List.of()))
                .thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE));
        var request = post("/api/v1/auth/email-challenges").contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"REGISTER\",\"email\":\"a@example.test\"}");
        mvc.perform(request).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
        mvc.perform(request).andExpect(status().isServiceUnavailable());
    }
}
