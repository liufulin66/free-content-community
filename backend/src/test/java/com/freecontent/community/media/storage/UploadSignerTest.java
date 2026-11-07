package com.freecontent.community.media.storage;

import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.dto.UploadTarget;
import com.freecontent.community.media.entity.MediaUpload;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class UploadSignerTest {
    private final UploadSigner signer = new UploadSigner(
            new StorageProperties("http://127.0.0.1:9000", "access", "secret", "media-bucket", "us-east-1"));

    @Test
    void signedTargetBindsDeclaredContentLength() {
        UploadTarget target = signer.sign(upload(), 68, null);

        assertEquals("68", target.headers().get("Content-Length"));
        assertNull(target.headers().get("x-amz-checksum-sha256"));
        assertTrue(signedHeaders(target).contains("content-length"));
    }

    @Test
    void signedTargetBindsOptionalSha256Checksum() {
        String sha256 = "ab".repeat(32);
        UploadTarget target = signer.sign(upload(), 68, sha256);

        String expected = java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(sha256));
        assertEquals(expected, target.headers().get("x-amz-checksum-sha256"));
        assertTrue(signedHeaders(target).contains("x-amz-checksum-sha256"));
    }

    private String signedHeaders(UploadTarget target) {
        return URLDecoder.decode(URI.create(target.url()).getRawQuery(), StandardCharsets.UTF_8);
    }

    private MediaUpload upload() {
        MediaUpload upload = new MediaUpload();
        upload.setId("upload1");
        upload.setAssetId("asset1");
        upload.setOwnerId("user1");
        upload.setStorageKey("private/user1/asset1/source");
        upload.setContentType("image/png");
        upload.setSignatureAt(LocalDateTime.of(2026, 11, 7, 0, 0));
        upload.setSignatureTtlSeconds(600);
        upload.setSignerId(signer.identity());
        upload.setExpiresAt(LocalDateTime.of(2026, 11, 7, 2, 0));
        return upload;
    }
}
