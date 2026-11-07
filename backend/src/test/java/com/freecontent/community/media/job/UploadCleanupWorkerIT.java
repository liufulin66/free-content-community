package com.freecontent.community.media.job;

import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.mapper.UploadCleanupTaskMapper;
import com.freecontent.community.media.storage.UploadObjectCleaner;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.jayway.jsonpath.JsonPath;
import io.minio.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class UploadCleanupWorkerIT {
    private static final String PREFIX = "test:cleanup-worker-it:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private StorageProperties storage;
    @Autowired private UploadCleanupWorker worker;
    @Autowired private UploadCleanupTaskMapper tasks;
    @MockitoSpyBean private UploadObjectCleaner cleaner;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> objects = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void isolation(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
        registry.add("app.media.upload-creation.redis-key-prefix", () -> PREFIX);
        registry.add("app.media.upload-cancel.redis-key-prefix", () -> PREFIX);
    }
    @AfterEach void cleanup() throws Exception {
        if (!objects.isEmpty()) try (MinioClient minio = minio()) {
            for (String key : objects) minio.removeObject(RemoveObjectArgs.builder().bucket(storage.bucket()).object(key).build());
        }
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String owner : userIds) {
            for (String table : List.of("media_upload_cleanup", "media_upload_request", "media_upload_session", "media_asset")) jdbc.update("DELETE FROM " + table + " WHERE owner_id=?", owner);
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", owner); users.deleteById(owner);
        }
    }

    @Test void delayedCleanupPreservesLatePutUntilDueAndThenDeletesThePrivateObject() throws Exception {
        Fixture fixture = fixture();
        put(fixture);
        worker.processTask(fixture.taskId());
        try (MinioClient minio = minio()) {
            assertEquals(68, minio.statObject(StatObjectArgs.builder().bucket(storage.bucket()).object(fixture.key()).build()).size());
        }
        assertEquals("PENDING", tasks.selectById(fixture.taskId()).getState());
        due(fixture);
        worker.processTask(fixture.taskId());
        assertEquals("SUCCEEDED", tasks.selectById(fixture.taskId()).getState());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT state FROM media_upload_session WHERE id=?", String.class, fixture.uploadId()));
        try (MinioClient minio = minio()) {
            assertFalse(minio.listObjects(ListObjectsArgs.builder().bucket(storage.bucket()).prefix(fixture.key()).build()).iterator().hasNext());
        }
    }

    @Test void recoveredLeaseRejectsAcknowledgementFromThePreviousOwner() throws Exception {
        Fixture fixture = fixture(); due(fixture);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        assertEquals(1, tasks.claim(fixture.taskId(), "old-owner", now, now.plusSeconds(60)));
        jdbc.update("UPDATE media_upload_cleanup SET lease_until=? WHERE id=?", now.minusSeconds(1), fixture.taskId());
        assertEquals(1, tasks.claim(fixture.taskId(), "new-owner", now, now.plusSeconds(60)));
        assertEquals(0, tasks.finish(fixture.taskId(), "old-owner", "SUCCEEDED", null, now));
        assertEquals(0, tasks.retry(fixture.taskId(), "old-owner", now, now.plusSeconds(5)));
        assertEquals("new-owner", tasks.selectById(fixture.taskId()).getLeaseToken());
        assertEquals(1, tasks.finish(fixture.taskId(), "new-owner", "SUCCEEDED", null, now));
    }

    @Test void concurrentWorkersCannotBothClaimTheSameTask() throws Exception {
        Fixture fixture = fixture(); due(fixture);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> { start.await(); return tasks.claim(fixture.taskId(), "worker-a", now, now.plusSeconds(60)); });
            Future<Integer> second = executor.submit(() -> { start.await(); return tasks.claim(fixture.taskId(), "worker-b", now, now.plusSeconds(60)); });
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            assertEquals(1, tasks.selectById(fixture.taskId()).getAttemptCount());
        } finally { executor.shutdownNow(); }
    }

    @Test void persistedRetrySurvivesAStorageFailureAndCanCompleteOnTheNextAttempt() throws Exception {
        Fixture fixture = fixture(); put(fixture); due(fixture);
        doThrow(new IOException("simulated")).doCallRealMethod().when(cleaner).deleteObject(fixture.key());
        worker.processTask(fixture.taskId());
        assertEquals("PENDING", tasks.selectById(fixture.taskId()).getState());
        assertEquals(1, tasks.selectById(fixture.taskId()).getAttemptCount());
        due(fixture);
        worker.processTask(fixture.taskId());
        assertEquals("SUCCEEDED", tasks.selectById(fixture.taskId()).getState());
        assertEquals(2, tasks.selectById(fixture.taskId()).getAttemptCount());
    }

    private void due(Fixture fixture) { jdbc.update("UPDATE media_upload_cleanup SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", fixture.taskId()); }
    private MinioClient minio() { return MinioClient.builder().endpoint(storage.endpoint()).region(storage.region()).credentials(storage.accessKey(), storage.secretKey()).build(); }
    private void put(Fixture fixture) throws Exception {
        objects.add(fixture.key());
        var request = HttpRequest.newBuilder(URI.create((String) fixture.target().get("url"))).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[68]));
        assertEquals(200, http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode());
    }
    private record Fixture(String uploadId, String taskId, String key, Map<String, Object> target) {
        @Override public String toString() { return "CleanupFixture[credentials=REDACTED]"; }
    }
    private Fixture fixture() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID().toString().replace("-", ""));
        user.setEmail("cleanup-worker-it-" + user.getId() + "@example.test"); user.setEmailNormalized(user.getEmail());
        user.setPasswordHash("unused-test-hash"); user.setEmailVerified(true); user.setEmailVerifiedAt(now);
        user.setDisplayName("cleanup-test"); user.setBio(""); user.setAccountStatus(UserAccount.Status.ACTIVE);
        user.setVersion(1); user.setAuthVersion(1L); user.setCreatedAt(now); user.setUpdatedAt(now);
        userIds.add(user.getId()); users.insert(user); roles.insertDefaultRole(user.getId(), now);
        String token = sessions.create(user.getId(), 1, "cleanup-test").accessToken();
        var create = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/media/uploads"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("scene", "CONTENT_IMAGE", "fileName", "cleanup.png", "contentType", "image/png", "sizeBytes", 68))));
        HttpResponse<String> created = http.send(create.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());
        String id = JsonPath.read(created.body(), "$.data.id");
        Map<String, Object> target = JsonPath.read(created.body(), "$.data.singleUploadTarget");
        var cancel = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/media/uploads/" + id + "?expectedVersion=1"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).DELETE();
        assertEquals(204, http.send(cancel.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        String taskId = jdbc.queryForObject("SELECT id FROM media_upload_cleanup WHERE upload_id=?", String.class, id);
        String key = jdbc.queryForObject("SELECT storage_key FROM media_upload_session WHERE id=?", String.class, id);
        assertTrue(key.startsWith("private/" + user.getId() + "/"));
        return new Fixture(id, taskId, key, target);
    }
}
