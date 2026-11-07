package com.freecontent.community.media.service;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.email-challenge.delivery-enabled=false")
class UploadPolicyFlowIT {
    private static final String PREFIX = "test:upload-policy-it:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private UserAccountMapper users;
    @Autowired private UserRoleMapper roles;
    @Autowired private RedisSessionStore sessions;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    private final List<String> fixtureIds = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void isolatedSessions(DynamicPropertyRegistry registry) {
        registry.add("app.auth.login.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        for (String id : fixtureIds) {
            jdbc.update("DELETE FROM app_user_role WHERE user_id=?", id);
            users.deleteById(id);
        }
    }

    @Test
    void authenticatedUserReceivesPolicyWithoutSecretsOrChangingAccountAndSessionExpiry() throws Exception {
        UserAccount account = fixture();
        String token = sessions.create(account.getId(), account.getAuthVersion(), "策略查询测试").accessToken();
        Instant expiresAt = sessions.find(token).expiresAt();

        HttpResponse<String> response = get("/api/v1/media/upload-policy", token);

        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
        Map<String, Object> root = JsonPath.read(response.body(), "$");
        assertEquals(Set.of("data", "requestId", "serverTime"), root.keySet());
        assertEquals(root.get("requestId"), response.headers().firstValue("X-Request-Id").orElseThrow());
        assertNotNull(Instant.parse((String) root.get("serverTime")));
        Map<String, Object> policy = JsonPath.read(response.body(), "$.data");
        assertEquals(Set.of("maxImageBytes", "maxVideoBytes", "allowedImageMimeTypes", "allowedVideoMimeTypes",
                "multipartThresholdBytes", "maxPartCount", "uploadUrlTtlSeconds"), policy.keySet());
        assertEquals(10485760L, ((Number) policy.get("maxImageBytes")).longValue());
        assertEquals(524288000L, ((Number) policy.get("maxVideoBytes")).longValue());
        assertEquals(List.of("image/jpeg", "image/png", "image/webp", "image/gif"), policy.get("allowedImageMimeTypes"));
        assertEquals(List.of("video/mp4"), policy.get("allowedVideoMimeTypes"));
        assertEquals(16777216L, ((Number) policy.get("multipartThresholdBytes")).longValue());
        assertEquals(10000, policy.get("maxPartCount"));
        assertEquals(600, policy.get("uploadUrlTtlSeconds"));
        UserAccount unchanged = users.selectById(account.getId());
        assertEquals(account.getVersion(), unchanged.getVersion());
        assertEquals(account.getAuthVersion(), unchanged.getAuthVersion());
        assertEquals(expiresAt, sessions.find(token).expiresAt());
    }

    @Test
    void generatedOpenApiDeclaresBearerAndRequiredPolicyFieldsWithContractBounds() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs", null);
        assertEquals(200, response.statusCode());
        Map<String, Object> operation = JsonPath.read(response.body(), "$.paths['/api/v1/media/upload-policy'].get");
        assertEquals("getUploadPolicy", operation.get("operationId"));
        assertFalse(operation.containsKey("requestBody"));
        assertTrue(!operation.containsKey("parameters") || ((List<?>) operation.get("parameters")).isEmpty());
        assertTrue(((Map<?, ?>) operation.get("responses")).containsKey("200"));
        assertTrue(((List<?>) operation.get("security")).stream()
                .anyMatch(value -> ((Map<?, ?>) value).containsKey("bearerAuth")));
        Map<String, Object> schema = JsonPath.read(response.body(), "$.components.schemas.UploadPolicy");
        assertNotNull(schema.get("required"), "UploadPolicy必须声明全部必填字段");
        assertEquals(Set.of("maxImageBytes", "maxVideoBytes", "allowedImageMimeTypes", "allowedVideoMimeTypes",
                "multipartThresholdBytes", "maxPartCount", "uploadUrlTtlSeconds"), Set.copyOf((List<?>) schema.get("required")));
        Map<?, ?> fields = (Map<?, ?>) schema.get("properties");
        for (String name : List.of("maxImageBytes", "maxVideoBytes", "multipartThresholdBytes", "maxPartCount", "uploadUrlTtlSeconds")) {
            Map<?, ?> field = (Map<?, ?>) fields.get(name);
            assertEquals("integer", field.get("type"));
            assertNotNull(field.get("minimum"), name);
            assertEquals(1, ((Number) field.get("minimum")).intValue());
        }
        assertEquals(9_007_199_254_740_991L, ((Number) ((Map<?, ?>) fields.get("maxImageBytes")).get("maximum")).longValue());
        assertEquals(9_007_199_254_740_991L, ((Number) ((Map<?, ?>) fields.get("maxVideoBytes")).get("maximum")).longValue());
        assertEquals(10000, ((Number) ((Map<?, ?>) fields.get("maxPartCount")).get("maximum")).intValue());
    }

    private UserAccount fixture() {
        UserAccount account = LoginTestSupport.account();
        account.setId(UUID.randomUUID().toString().replace("-", ""));
        account.setEmail("upload-policy-it-" + account.getId() + "@example.test");
        account.setEmailNormalized(account.getEmail());
        fixtureIds.add(account.getId());
        users.insert(account);
        roles.insertDefaultRole(account.getId(), account.getCreatedAt());
        return account;
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
