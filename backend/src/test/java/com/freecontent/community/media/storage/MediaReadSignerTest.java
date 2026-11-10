package com.freecontent.community.media.storage;

import com.freecontent.community.common.error.*;
import com.freecontent.community.media.config.StorageProperties;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static org.junit.jupiter.api.Assertions.*;

class MediaReadSignerTest {
    private final StorageProperties storage = new StorageProperties("http://127.0.0.1:9000","access","secret","free-content","us-east-1");
    private final MediaReadSigner signer = new MediaReadSigner(storage);
    private final Instant issued = Instant.parse("2026-11-09T10:00:00Z");
    private MediaObjectReader.ObjectReference object(String key,String identity,String type,long size) {
        return new MediaObjectReader.ObjectReference("asset_1","user_1",key,"etag",identity,type,size);
    }
    private MediaObjectReader.ObjectReference valid() { return object("private/user_1/asset_1/ready/object_1",storage.identity(),"image/png",68); }

    @Test void signedQueryBindsTheObjectReadHeadersAndLifetimeWithoutExposingCredentialsInToString() {
        var access = signer.sign(valid(),issued,300); var url = HttpUrl.get(access.url());
        assertEquals("/free-content/private/user_1/asset_1/ready/object_1",url.encodedPath());
        assertEquals("300",url.queryParameter("X-Amz-Expires")); assertEquals("20261109T100000Z",url.queryParameter("X-Amz-Date"));
        assertEquals("image/png",url.queryParameter("response-content-type")); assertEquals("no-store",url.queryParameter("response-cache-control"));
        assertEquals("inline",url.queryParameter("response-content-disposition"));
        assertEquals(issued.plusSeconds(300),access.expiresAt()); assertFalse(access.toString().contains("X-Amz-")); assertFalse(storage.toString().contains("secret"));
    }

    @Test void expirationExactlyMatchesTheWholeSecondsEncodedInTheSignature() {
        var access = signer.sign(valid(),issued.plusNanos(123456789),300);
        assertEquals(issued.truncatedTo(ChronoUnit.SECONDS).plusSeconds(300),access.expiresAt());
    }

    @ParameterizedTest @ValueSource(strings = {"SOURCE","OTHER_OWNER","OTHER_ASSET","TRAVERSAL","STORAGE","TYPE","SIZE"})
    void invalidReferencesCannotBeSigned(String scenario) {
        var reference = switch(scenario) {
            case "SOURCE" -> object("private/user_1/asset_1/source",storage.identity(),"image/png",68);
            case "OTHER_OWNER" -> object("private/other/asset_1/ready/object_1",storage.identity(),"image/png",68);
            case "OTHER_ASSET" -> object("private/user_1/other/ready/object_1",storage.identity(),"image/png",68);
            case "TRAVERSAL" -> object("private/user_1/asset_1/ready/../source",storage.identity(),"image/png",68);
            case "STORAGE" -> object("private/user_1/asset_1/ready/object_1","wrong","image/png",68);
            case "TYPE" -> object("private/user_1/asset_1/ready/object_1",storage.identity(),"text/html",68);
            default -> object("private/user_1/asset_1/ready/object_1",storage.identity(),"image/png",0);
        };
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,assertThrows(BusinessException.class,() -> signer.sign(reference,issued,300)).errorCode());
    }

    @ParameterizedTest @ValueSource(ints = {0,-1,604801})
    void lifetimesOutsideS3BoundsFailClosed(int ttl) {
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE,assertThrows(BusinessException.class,() -> signer.sign(valid(),issued,ttl)).errorCode());
    }
}
