package com.freecontent.community.media.storage;

import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.StorageProperties;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

/** HEAD只核对存储侧结果；实际格式和内容校验由后续处理器完成。 */
@Component
public class UploadObjectInspector implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(UploadObjectInspector.class);
    private final MinioClient client;
    private final String bucket;

    public UploadObjectInspector(StorageProperties storage) {
        bucket = storage.bucket();
        var http = new OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS).build();
        client = MinioClient.builder().endpoint(storage.endpoint()).region(storage.region())
                .credentials(storage.accessKey(), storage.secretKey()).httpClient(http, true).build();
    }

    public record StoredObject(long sizeBytes, String contentType, String etag,
                               String versionId, LocalDateTime lastModified) { }

    public StoredObject inspect(String key) {
        if (key == null || !key.startsWith("private/")) throw unavailable();
        try {
            var object = client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return new StoredObject(object.size(), object.contentType(), object.etag(), object.versionId(),
                    LocalDateTime.ofInstant(object.lastModified().toInstant(), ZoneOffset.UTC));
        } catch (ErrorResponseException ex) {
            if ("NoSuchKey".equals(ex.errorResponse().code()) || "NoSuchObject".equals(ex.errorResponse().code())) {
                throw new BusinessException(ErrorCode.UPLOAD_STATE_CONFLICT, "存储对象尚不存在，请先完成文件上传");
            }
            log.warn("Upload object check unavailable, errorType={}", ex.getClass().getSimpleName());
            throw unavailable();
        } catch (Exception ex) {
            log.warn("Upload object check unavailable, errorType={}", ex.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "上传存储检查暂不可用"); }
    @Override @PreDestroy public void close() throws Exception { client.close(); }
}
