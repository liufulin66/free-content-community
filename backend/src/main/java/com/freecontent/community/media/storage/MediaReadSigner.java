package com.freecontent.community.media.storage;

import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.dto.MediaAccess;
import io.minio.Signer;
import io.minio.Time;
import io.minio.errors.MinioException;
import okhttp3.HttpUrl;
import okhttp3.Request;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** 本地SigV4 GET签名，无网络调用；响应覆盖头也参与签名。 */
@Component
public class MediaReadSigner {
    private final StorageProperties storage;
    public MediaReadSigner(StorageProperties storage) { this.storage = storage; }
    public MediaAccess sign(MediaObjectReader.ObjectReference object,Instant issuedAt,int ttlSeconds) {
        object.requireStorage(storage);
        if (ttlSeconds < 1 || ttlSeconds > 604800) throw unavailable();
        Instant signingTime = issuedAt.truncatedTo(ChronoUnit.SECONDS);
        try {
            HttpUrl url = HttpUrl.get(storage.endpoint()).newBuilder().addPathSegment(storage.bucket()).addPathSegments(object.key())
                    .addQueryParameter("response-content-type",object.contentType()).addQueryParameter("response-content-disposition","inline")
                    .addQueryParameter("response-cache-control","no-store").build();
            var request = new Request.Builder().url(url).get().header("Host",url.uri().getRawAuthority())
                    .header("X-Amz-Date",Time.AMZ_DATE_FORMAT.format(signingTime.atZone(ZoneOffset.UTC))).build();
            String signed = Signer.presignV4(request,storage.region(),storage.accessKey(),storage.secretKey(),ttlSeconds).toString();
            return new MediaAccess(object.assetId(),signed,signingTime.plusSeconds(ttlSeconds),object.contentType());
        } catch (MinioException | RuntimeException ex) { throw unavailable(); }
    }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"媒体读取签名服务暂不可用"); }
}
