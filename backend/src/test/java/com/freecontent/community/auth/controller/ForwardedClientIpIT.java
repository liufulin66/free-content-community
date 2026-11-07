package com.freecontent.community.auth.controller;

import com.freecontent.community.auth.security.TokenDigest;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.email-challenge.delivery-enabled=false")
class ForwardedClientIpIT {
    private static final String PREFIX = "test:forwarded-ip:" + UUID.randomUUID() + ":";
    @Value("${local.server.port}") private int port;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void redisPrefix(DynamicPropertyRegistry registry) {
        registry.add("app.auth.email-challenge.redis-key-prefix", () -> PREFIX);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM email_challenge WHERE email_normalized LIKE 'forwarded-ip-%@example.test'",
                String.class);
        for (String id : ids) {
            jdbc.update("DELETE FROM email_delivery_task WHERE challenge_id=?", id);
            jdbc.update("DELETE FROM email_challenge WHERE id=?", id);
        }
    }

    @Test
    void trustedProxyForwardedForDeterminesRateLimitSource() throws Exception {
        String email = "forwarded-ip-" + UUID.randomUUID() + "@example.test";
        String body = "{\"purpose\":\"REGISTER\",\"email\":\"" + email + "\"}";
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/auth/email-challenges"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", "203.0.113.9")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(202, response.statusCode(), response.body());
        assertTrue(redis.hasKey(PREFIX + "{email-challenge}:source:" + TokenDigest.sha256("203.0.113.9")),
                "source quota must use X-Forwarded-For from a trusted proxy");
        assertFalse(redis.hasKey(PREFIX + "{email-challenge}:source:" + TokenDigest.sha256("127.0.0.1")),
                "source quota must not fall back to the proxy address");
    }
}
