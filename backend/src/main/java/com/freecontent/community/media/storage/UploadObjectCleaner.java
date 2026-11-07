package com.freecontent.community.media.storage;

import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.config.UploadCleanupProperties;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/** 存储I/O有总超时；只能由已确认取消且校验通过的后台任务调用。 */
@Component
public class UploadObjectCleaner implements AutoCloseable {
    private final MinioClient client;
    private final String bucket;

    public UploadObjectCleaner(StorageProperties storage) {
        bucket = storage.bucket();
        OkHttpClient http = new OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS).callTimeout(UploadCleanupProperties.STORAGE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build();
        client = MinioClient.builder().endpoint(storage.endpoint()).region(storage.region())
                .credentials(storage.accessKey(), storage.secretKey()).httpClient(http, true).build();
    }

    public void deleteObject(String key) throws Exception {
        if (key == null || !key.startsWith("private/")) throw new IllegalArgumentException("仅允许清理私有上传对象");
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }

    @Override @PreDestroy
    public void close() throws Exception { client.close(); }
}
