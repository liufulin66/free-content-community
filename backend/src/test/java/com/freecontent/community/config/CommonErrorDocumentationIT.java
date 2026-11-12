package com.freecontent.community.config;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.error.ErrorCode;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springframework.web.method.HandlerMethod;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class CommonErrorDocumentationIT {
    @Value("${local.server.port}") private int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void successSchemasAndOperationSpecificHeadersRemainAvailable() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString()); assertEquals(200, response.statusCode());
        for (String[] endpoint : List.of(new String[]{"/api/v1/admin/tags", "post", "201"},
                new String[]{"/api/v1/admin/reviews", "get", "200"},
                new String[]{"/api/v1/admin/reviews/{reviewId}", "get", "200"},
                new String[]{"/api/v1/admin/reviews/{reviewId}/media/{assetId}/access", "get", "200"},
                new String[]{"/api/v1/admin/reviews/{reviewId}/decisions", "post", "200"},
                new String[]{"/api/v1/creator/contents/{contentId}/reviews/{reviewId}/cancellation", "put", "200"},
                new String[]{"/api/v1/media/uploads/{uploadId}/completion", "post", "202"},
                new String[]{"/api/v1/creator/contents", "post", "201"},
                new String[]{"/api/v1/creator/contents/{contentId}/reviews", "post", "201"},
                new String[]{"/api/v1/creator/contents/{contentId}/reviews/{reviewId}", "get", "200"},
                new String[]{"/api/v1/creator/contents/{contentId}", "get", "200"},
                new String[]{"/api/v1/creator/contents/{contentId}/draft", "patch", "200"},
                new String[]{"/api/v1/creator/contents", "get", "200"},
                new String[]{"/api/v1/creator/contents/{contentId}/revisions/{revisionId}/media/{assetId}/access", "get", "200"},
                new String[]{"/api/v1/media/assets/{assetId}", "get", "200"}, new String[]{"/api/v1/tasks/{taskId}", "get", "200"})) {
            Map<String, Object> success = JsonPath.read(response.body(), "$.paths['" + endpoint[0] + "']." + endpoint[1] + ".responses['" + endpoint[2] + "']");
            Map<?, ?> content = (Map<?, ?>) success.get("content");
            assertNotNull(content); assertFalse(content.isEmpty());
            for (Object representation : content.values()) {
                assertNotNull(((Map<?, ?>) ((Map<?, ?>) representation).get("schema")).get("$ref"));
            }
            Map<?, ?> headers = (Map<?, ?>) success.get("headers");
            assertTrue(headers.keySet().containsAll(Set.of("X-Request-Id", "Cache-Control")));
            if (!"200".equals(endpoint[2])) assertTrue(headers.containsKey("Location"));
            if ("202".equals(endpoint[2])) assertTrue(headers.containsKey("Retry-After"));
        }
    }

    @Test
    void anExplicitErrorSetCanOptOutOfAuthenticatedDefaults() throws Exception {
        var handler = new HandlerMethod(new ExplicitContract(), ExplicitContract.class.getDeclaredMethod("create"));
        var operation = new Operation().responses(new ApiResponses().addApiResponse("201", new ApiResponse().description("created")));
        new ProblemResponseCustomizer().controllerErrorDocumentation().customize(operation, handler);
        assertEquals(Set.of("201", "409"), operation.getResponses().keySet());
    }

    @Test
    void publicOperationCannotUseAuthenticatedCommonErrors() throws Exception {
        var handler = new HandlerMethod(new DefaultErrorContract(), DefaultErrorContract.class.getDeclaredMethod("create"));
        var operation = new Operation().responses(new ApiResponses());
        assertThrows(IllegalStateException.class,
                () -> new ProblemResponseCustomizer().controllerErrorDocumentation().customize(operation, handler));
    }

    @Test
    void securedOperationMayUseAuthenticatedCommonErrors() throws Exception {
        var handler = new HandlerMethod(new DefaultErrorContract(), DefaultErrorContract.class.getDeclaredMethod("create"));
        var operation = new Operation()
                .security(List.of(new SecurityRequirement().addList("bearerAuth")))
                .responses(new ApiResponses());
        new ProblemResponseCustomizer().controllerErrorDocumentation().customize(operation, handler);
        assertTrue(operation.getResponses().containsKey("401"));
        assertTrue(operation.getResponses().containsKey("403"));
    }

    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;

    @Test
    void everyMappedControllerMethodDeclaresApiErrorResponses() {
        for (HandlerMethod handler : mappings.getHandlerMethods().values()) {
            if (!handler.getBeanType().getPackageName().startsWith("com.freecontent.community")) continue;
            boolean declared = AnnotatedElementUtils.hasAnnotation(handler.getMethod(), ApiErrorResponses.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), ApiErrorResponses.class);
            assertTrue(declared, handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName()
                    + " 缺少 @ApiErrorResponses");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyDocumentedOperationHasProblemResponsesWithCommonHeaders() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(15)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        Map<String, Map<String, Object>> paths = JsonPath.read(response.body(), "$.paths");
        Set<String> methods = Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
        for (var path : paths.entrySet()) {
            for (var operation : path.getValue().entrySet()) {
                if (!methods.contains(operation.getKey())) continue;
                Map<String, Object> operationObject = (Map<String, Object>) operation.getValue();
                Map<String, Object> responses = (Map<String, Object>) operationObject.get("responses");
                assertNotNull(responses, path.getKey() + " " + operation.getKey() + " 缺少响应定义");
                boolean hasProblem = false;
                for (var entry : responses.entrySet()) {
                    if (!entry.getKey().matches("[45]\\d\\d")) continue;
                    hasProblem = true;
                    Map<String, Object> problem = (Map<String, Object>) entry.getValue();
                    Map<String, Object> headers = (Map<String, Object>) problem.get("headers");
                    assertNotNull(headers, path.getKey() + " " + operation.getKey() + " " + entry.getKey() + " 缺少公共响应头");
                    assertTrue(headers.keySet().containsAll(Set.of("X-Request-Id", "Cache-Control")),
                            path.getKey() + " " + operation.getKey() + " " + entry.getKey() + " 缺少 X-Request-Id/Cache-Control");
                }
                assertTrue(hasProblem, path.getKey() + " " + operation.getKey() + " 没有任何4xx/5xx错误文档");
            }
        }
    }

    @ParameterizedTest
    @MethodSource("contractOperations")
    @SuppressWarnings("unchecked")
    void everyOperationMatchesTheVersionedErrorContract(String operationId, Map<String, Object> expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(15)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        Map<String, Object> operation = JsonPath.read(response.body(),
                "$.paths['" + expected.get("path") + "']." + expected.get("method"));
        assertEquals(operationId, operation.get("operationId"));
        Map<String, Object> actual = (Map<String, Object>) operation.get("responses");
        Map<String, List<String>> required = (Map<String, List<String>>) expected.get("responses");
        assertEquals(required.keySet(), actual.keySet(), operationId + " 响应状态与契约不一致");
        for (var entry : required.entrySet()) {
            Map<String, Object> problem = (Map<String, Object>) actual.get(entry.getKey());
            Map<?, ?> headers = (Map<?, ?>) problem.get("headers");
            assertNotNull(headers, operationId + " " + entry.getKey() + " 缺少响应头");
            assertTrue(headers.keySet().containsAll(Set.of("X-Request-Id", "Cache-Control")));
            if (entry.getValue().isEmpty()) continue;
            Map<?, ?> content = (Map<?, ?>) problem.get("content");
            Map<?, ?> media = (Map<?, ?>) content.get("application/problem+json");
            Map<?, ?> schema = (Map<?, ?>) media.get("schema");
            List<Map<String, Object>> allOf = (List<Map<String, Object>>) schema.get("allOf");
            Map<String, Object> properties = (Map<String, Object>) allOf.get(1).get("properties");
            assertEquals(Integer.parseInt(entry.getKey()),
                    ((Number) ((Map<?, ?>) properties.get("status")).get("const")).intValue());
            List<String> codes = (List<String>) ((Map<?, ?>) properties.get("code")).get("enum");
            assertEquals(new HashSet<>(entry.getValue()), new HashSet<>(codes),
                    operationId + " " + entry.getKey() + " 错误码与契约不一致");
            if ("401".equals(entry.getKey())) assertTrue(headers.containsKey("WWW-Authenticate"));
            if ("429".equals(entry.getKey()) || entry.getValue().contains("REQUEST_IN_PROGRESS")) {
                assertTrue(headers.containsKey("Retry-After"));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theContractSnapshotCoversEveryDocumentedOperation() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(15)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        Set<String> actual = new HashSet<>(JsonPath.<List<String>>read(response.body(), "$.paths.*.*.operationId"));
        Set<String> expected = contractOperations().map(args -> (String) args.get()[0])
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(expected, actual, "新增接口必须同步契约基准");
    }

    @SuppressWarnings("unchecked")
    static Stream<Arguments> contractOperations() throws Exception {
        try (var input = CommonErrorDocumentationIT.class.getResourceAsStream("/contracts/api-v1-error-responses.json")) {
            assertNotNull(input, "缺少固定版本的接口契约基准");
            Map<String, Map<String, Object>> operations = JsonPath.parse(input).read("$.operations");
            return operations.entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
        }
    }

    private static class ExplicitContract {
        @ApiErrorResponses(value = ErrorCode.TAG_NAME_CONFLICT, includeCommon = false)
        public void create() { }
    }

    private static class DefaultErrorContract {
        @ApiErrorResponses
        public void create() { }
    }

}
