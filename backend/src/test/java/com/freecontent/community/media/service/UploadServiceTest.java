package com.freecontent.community.media.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.*;
import com.freecontent.community.media.dto.CreateUploadRequest;
import com.freecontent.community.media.dto.UploadSession;
import com.freecontent.community.media.dto.UploadTarget;
import com.freecontent.community.media.entity.MediaAsset;
import com.freecontent.community.media.entity.MediaUpload;
import com.freecontent.community.media.entity.UploadRequestRecord;
import com.freecontent.community.media.ratelimit.UploadCreationRateLimiter;
import com.freecontent.community.media.service.impl.UploadServiceImpl;
import com.freecontent.community.media.service.transaction.UploadTransactionService;
import com.freecontent.community.media.storage.UploadSigner;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadServiceTest {
    @Test
    void failureToBeginATransactionReturns503WithoutIssuingUploadPermission() {
        var transactions = mock(UploadTransactionService.class);
        var signer = mock(UploadSigner.class);
        var limiter = mock(UploadCreationRateLimiter.class);
        when(transactions.claim(any(), anyString(), anyString())).thenThrow(new CannotCreateTransactionException("simulated unavailable database"));
        var json = JsonMapper.builder().build();
        var service = new UploadServiceImpl(new UploadPolicyProperties(10485760, 524288000,
                List.of("image/png"), List.of("video/mp4"), 16777216, 10000, 600),
                new UploadCreationProperties(7200, 60, 100, "test:upload:"), transactions, signer, Clock.systemUTC(), json, limiter);
        var request = json.convertValue(Map.of("scene", "AVATAR", "fileName", "avatar.png", "contentType", "image/png", "sizeBytes", 68), CreateUploadRequest.class);
        var principal = new SessionPrincipal("session1", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)), "digest", 1);
        BusinessException failure = assertThrows(BusinessException.class, () -> service.create(request, UUID.randomUUID().toString(), principal, "127.0.0.1"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.errorCode());
        verifyNoInteractions(signer);
    }

    @Test
    void replayReturnsOriginalResultWithoutConsumingRateLimit() {
        var transactions = mock(UploadTransactionService.class);
        var signer = mock(UploadSigner.class);
        var limiter = mock(UploadCreationRateLimiter.class);
        var json = JsonMapper.builder().build();
        var service = service(transactions, signer, limiter, json);
        UploadRequestRecord record = record();
        MediaUpload replay = new MediaUpload();
        replay.setId("upload1");
        replay.setAssetId("asset1");
        replay.setExpiresAt(LocalDateTime.of(2026, 11, 7, 0, 0));
        when(transactions.claim(any(), anyString(), anyString()))
                .thenReturn(new UploadTransactionService.Claim(record, replay, replayAsset()));
        when(signer.sign(any(MediaUpload.class), anyLong(), any())).thenReturn(
                new UploadTarget("https://storage.example/object", "PUT", Map.of(),
                        Instant.parse("2026-11-07T00:10:00Z")));

        UploadSession result = service.create(request(json), UUID.randomUUID().toString(), principal(), "127.0.0.1");

        assertEquals("upload1", result.id());
        verifyNoInteractions(limiter);
    }

    @Test
    void rateLimitedNewRequestReleasesClaim() {
        var transactions = mock(UploadTransactionService.class);
        var signer = mock(UploadSigner.class);
        var limiter = mock(UploadCreationRateLimiter.class);
        var json = JsonMapper.builder().build();
        var service = service(transactions, signer, limiter, json);
        UploadTransactionService.Claim claim = new UploadTransactionService.Claim(record(), null, null);
        when(transactions.claim(any(), anyString(), anyString())).thenReturn(claim);
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED)).when(limiter).acquire(anyString(), anyString());

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.create(request(json), UUID.randomUUID().toString(), principal(), "127.0.0.1"));

        assertEquals(ErrorCode.RATE_LIMITED, failure.errorCode());
        verify(transactions).release(claim);
        verifyNoInteractions(signer);
    }

    private UploadServiceImpl service(UploadTransactionService transactions, UploadSigner signer,
                                      UploadCreationRateLimiter limiter, JsonMapper json) {
        return new UploadServiceImpl(new UploadPolicyProperties(10485760, 524288000,
                List.of("image/png"), List.of("video/mp4"), 16777216, 10000, 600),
                new UploadCreationProperties(7200, 60, 100, "test:upload:"), transactions, signer,
                Clock.systemUTC(), json, limiter);
    }

    private CreateUploadRequest request(JsonMapper json) {
        return json.convertValue(Map.of("scene", "AVATAR", "fileName", "avatar.png",
                "contentType", "image/png", "sizeBytes", 68), CreateUploadRequest.class);
    }

    private SessionPrincipal principal() {
        return new SessionPrincipal("session1", CurrentUser.from(LoginTestSupport.account(), List.of(UserRole.USER)),
                "digest", 1);
    }

    private UploadRequestRecord record() {
        UploadRequestRecord record = new UploadRequestRecord();
        record.setId("request1");
        record.setLeaseToken("lease1");
        return record;
    }

    private MediaAsset replayAsset() {
        MediaAsset asset = new MediaAsset();
        asset.setId("asset1");
        asset.setOwnerId("user1");
        asset.setDeclaredSizeBytes(68L);
        return asset;
    }
}
