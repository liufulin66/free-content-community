package com.freecontent.community.media.storage;

import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.entity.MediaAsset;
import io.minio.*;
import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.Locale;

/** 网络HEAD位于内容授权事务之外；只检查已绑定结果对象，不将源文件提升为READY。 */
@Component
public class MediaObjectReader implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MediaObjectReader.class);
    private final StorageProperties storage;
    private final MinioClient client;
    public MediaObjectReader(StorageProperties storage) {
        this.storage = storage;
        var http = new OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS)
                .writeTimeout(5,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS).build();
        client = MinioClient.builder().endpoint(storage.endpoint()).region(storage.region()).credentials(storage.accessKey(),storage.secretKey()).httpClient(http,true).build();
    }

    public record ObjectReference(String assetId,String ownerId,String key,String etag,String storageId,String contentType,long sizeBytes) {
        /** 本人和后台预览共用结果对象校验；调用方先完成授权、引用及READY检查。 */
        public static ObjectReference fromAsset(String assetId, String owner, MediaAsset asset, StorageProperties storage) {
            if (asset.getActualContentType() == null || asset.getActualSizeBytes() == null) throw unavailable();
            String prefix = asset.getScene().equals("CONTENT_IMAGE") ? "image/" : "video/";
            if (!asset.getActualContentType().toLowerCase(Locale.ROOT).startsWith(prefix)) throw unavailable();
            var result = new ObjectReference(assetId, owner, asset.getReadyObjectKey(), asset.getReadyObjectEtag(),
                    asset.getReadyStorageId(), asset.getActualContentType(), asset.getActualSizeBytes());
            result.requireStorage(storage); return result;
        }

        public void requireStorage(StorageProperties storage) {
            if (assetId == null || !assetId.matches("[A-Za-z0-9_-]{1,64}") || ownerId == null || !ownerId.matches("[A-Za-z0-9_-]{1,64}")
                    || key == null || etag == null || etag.isBlank() || !storage.identity().equals(storageId)
                    || contentType == null || contentType.isBlank() || sizeBytes < 1 || sizeBytes > 9007199254740991L) throw unavailable();
            String prefix = "private/"+ownerId+"/"+assetId+"/ready/";
            if (!key.startsWith(prefix) || !key.substring(prefix.length()).matches("[A-Za-z0-9_-]{1,64}")) throw unavailable();
            try {
                MediaType type = MediaType.parseMediaType(contentType);
                if (type.isWildcardType() || type.isWildcardSubtype() || !(type.getType().equals("image") || type.getType().equals("video"))) throw unavailable();
            } catch (IllegalArgumentException ex) { throw unavailable(); }
        }
    }

    public void verify(ObjectReference expected) {
        expected.requireStorage(storage);
        try {
            var actual = client.statObject(StatObjectArgs.builder().bucket(storage.bucket()).object(expected.key()).build());
            if (actual.size() != expected.sizeBytes() || !expected.etag().equals(actual.etag())) throw unavailable();
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) {
            log.warn("Media result check unavailable, assetId={}, errorType={}",expected.assetId(),ex.getClass().getSimpleName());
            throw unavailable();
        }
    }
    private static BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"已校验媒体对象暂不可用"); }
    @Override @PreDestroy public void close() throws Exception { client.close(); }
}
