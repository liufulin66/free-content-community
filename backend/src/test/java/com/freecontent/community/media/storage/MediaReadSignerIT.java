package com.freecontent.community.media.storage;

import com.freecontent.community.media.config.StorageProperties;
import io.minio.*;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.auth.email-challenge.delivery-enabled=false","app.media.upload-cleanup.enabled=false"})
class MediaReadSignerIT {
    @Autowired private StorageProperties storage;
    @Autowired private MediaReadSigner signer;
    private final String owner = id(),asset = id(),key = "private/"+owner+"/"+asset+"/ready/"+id();
    private final byte[] bytes = new byte[]{(byte)137,80,78,71,13,10,26,10};
    private final HttpClient http = HttpClient.newHttpClient();
    private MinioClient client() { return MinioClient.builder().endpoint(storage.endpoint()).region(storage.region()).credentials(storage.accessKey(),storage.secretKey()).build(); }
    @AfterEach void cleanup() throws Exception { try (var minio = client()) { minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build()); } }
    @Test void rangeReadsWorkWhileExpiredAndTamperedSignaturesAreRejected() throws Exception {
        String etag;
        try (var minio = client()) {
            etag = minio.putObject(PutObjectArgs.builder().bucket(storage.bucket()).object(key).contentType("image/png")
                    .stream(new ByteArrayInputStream(bytes),(long)bytes.length,-1L).build()).etag();
        }
        var object = new MediaObjectReader.ObjectReference(asset,owner,key,etag,storage.identity(),"image/png",bytes.length);
        var access = signer.sign(object,Instant.now(),300);
        var part = http.send(HttpRequest.newBuilder(URI.create(access.url())).header("Range","bytes=0-3").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206,part.statusCode()); assertArrayEquals(Arrays.copyOfRange(bytes,0,4),part.body());
        String expired = signer.sign(object,Instant.now().minusSeconds(5),1).url(); assertEquals(403,status(expired));
        String changedTtl = HttpUrl.get(access.url()).newBuilder().setQueryParameter("X-Amz-Expires","600").build().toString(); assertEquals(403,status(changedTtl));
        String changedType = HttpUrl.get(access.url()).newBuilder().setQueryParameter("response-content-type","text/html").build().toString(); assertEquals(403,status(changedType));
    }
    private int status(String url) throws Exception { return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode(); }
    private static String id() { return UUID.randomUUID().toString().replace("-",""); }
}
