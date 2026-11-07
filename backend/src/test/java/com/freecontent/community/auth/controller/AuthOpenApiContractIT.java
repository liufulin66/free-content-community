package com.freecontent.community.auth.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 通过真实HTTP检查生成文档，不用反射注解代替最终OpenAPI结果。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class AuthOpenApiContractIT {
    @Value("${local.server.port}") private int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @ParameterizedTest
    @CsvSource({"email-challenges,202", "registrations,201", "sessions,201"})
    void generatedSuccessStatusMatchesTheContract(String resource, String expectedStatus) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(15)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        Map<String, Object> responses = JsonPath.read(response.body(),
                "$.paths['/api/v1/auth/" + resource + "'].post.responses");
        assertTrue(responses.containsKey(expectedStatus), () -> "应声明" + expectedStatus + "，实际声明" + responses.keySet());
        assertFalse(responses.containsKey("200"), "不得把不使用的200声明为成功响应");
    }
}
