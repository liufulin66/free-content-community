package com.freecontent.community.media.storage;

import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.dto.UploadTarget;
import com.freecontent.community.media.entity.MediaUpload;

import io.minio.Signer;
import io.minio.Time;
import io.minio.errors.MinioException;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * 使用SDK的SigV4实现，固定签名时间以重建幂等结果；不访问存储网络或持久化签名URL。
 */
@Component
public class UploadSigner {
    private static final Logger log = LoggerFactory.getLogger(UploadSigner.class);

    private final StorageProperties storage;

    public UploadSigner(StorageProperties storage) {
        this.storage = storage;
    }

    public String identity() {
        return storage.identity();
    }

    public UploadTarget sign(MediaUpload upload, long contentLength, String sha256Hex) {
        if (!identity().equals(upload.getSignerId())) {
            throw unavailable();
        }
        try {
            HttpUrl url = HttpUrl.get(storage.endpoint()).newBuilder().addPathSegment(storage.bucket())
                    .addPathSegments(upload.getStorageKey()).build();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", upload.getContentType());
            headers.put("Content-Length", Long.toString(contentLength));
            if (sha256Hex != null) headers.put("x-amz-checksum-sha256", checksumBase64(sha256Hex));

            Request.Builder builder = new Request.Builder().url(url)
                    .header("Host", url.uri().getRawAuthority())
                    .header("X-Amz-Date", Time.AMZ_DATE_FORMAT.format(upload.getSignatureAt().atZone(ZoneOffset.UTC)))
                    .put(RequestBody.create(new byte[0], null));
            headers.forEach(builder::header);
            String signed = Signer.presignV4(builder.build(), storage.region(), storage.accessKey(), storage.secretKey(), upload.getSignatureTtlSeconds()).toString();
            return new UploadTarget(signed, "PUT", Map.copyOf(headers),
                    upload.getSignatureAt().toInstant(ZoneOffset.UTC).plusSeconds(upload.getSignatureTtlSeconds()));
        } catch (MinioException | RuntimeException ex) {
            log.warn("Upload signing failed, uploadId={}, errorType={}", upload.getId(), ex.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private String checksumBase64(String sha256Hex) {
        byte[] digest = HexFormat.of().parseHex(sha256Hex);
        if (digest.length != 32) throw new IllegalArgumentException("SHA-256 长度不正确");
        return Base64.getEncoder().encodeToString(digest);
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "上传签名服务暂不可用");
    }
}
