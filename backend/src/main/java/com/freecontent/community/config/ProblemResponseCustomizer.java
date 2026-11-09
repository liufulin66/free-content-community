package com.freecontent.community.config;

import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.error.ErrorCode;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** 将@ApiErrorResponses声明转换为统一的Problem Details OpenAPI响应。 */
@Configuration
public class ProblemResponseCustomizer {
    private static final Set<ErrorCode> COMMON_ERRORS = Set.of(ErrorCode.MALFORMED_REQUEST, ErrorCode.AUTHENTICATION_REQUIRED,
            ErrorCode.PERMISSION_DENIED, ErrorCode.ACCOUNT_RESTRICTED,
            ErrorCode.VALIDATION_ERROR, ErrorCode.RATE_LIMITED, ErrorCode.INTERNAL_ERROR, ErrorCode.DEPENDENCY_UNAVAILABLE);

    @Bean
    public OperationCustomizer controllerErrorDocumentation() {
        return (operation, handler) -> {
            ApiErrorResponses declaration = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), ApiErrorResponses.class);
            if (declaration == null) declaration = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), ApiErrorResponses.class);
            if (declaration == null) return operation;
            if (declaration.includeCommon() && !isSecured(operation, handler)) {
                throw new IllegalStateException("@ApiErrorResponses(includeCommon=true) 只能用于受保护接口；"
                        + "公开接口必须使用 includeCommon=false 并显式声明错误码: " + handler);
            }
            EnumSet<ErrorCode> codes = declaration.includeCommon() ? EnumSet.copyOf(COMMON_ERRORS) : EnumSet.noneOf(ErrorCode.class);
            Collections.addAll(codes, declaration.value());
            if (operation.getResponses() == null) operation.setResponses(new ApiResponses());
            // 单个声明决定错误集合，避免叠加旧注解或不属于本接口的公共错误。
            operation.getResponses().entrySet().removeIf(entry -> entry.getKey().matches("[45]\\d\\d"));
            operation.getResponses().forEach((status, response) -> {
                if (status.matches("2\\d\\d") && response.get$ref() == null) commonHeaders(response);
            });
            Map<Integer, List<ErrorCode>> grouped = codes.stream()
                    .collect(Collectors.groupingBy(ErrorCode::statusCode, TreeMap::new, Collectors.toList()));
            grouped.forEach((status, errors) -> operation.getResponses().addApiResponse(Integer.toString(status), errorResponse(status, errors)));
            return operation;
        };
    }

    private boolean isSecured(io.swagger.v3.oas.models.Operation operation, HandlerMethod handler) {
        if (operation.getSecurity() != null && !operation.getSecurity().isEmpty()) return true;
        var annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), io.swagger.v3.oas.annotations.Operation.class);
        if (annotation != null && annotation.security().length > 0) return true;
        return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), io.swagger.v3.oas.annotations.security.SecurityRequirement.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), io.swagger.v3.oas.annotations.security.SecurityRequirement.class);
    }

    private ApiResponse errorResponse(int status, List<ErrorCode> errors) {
        var fields = new ObjectSchema();
        fields.addProperty("status", new IntegerSchema()._const(status));
        fields.addProperty("code", new StringSchema()._enum(errors.stream().map(Enum::name).toList()));
        var body = new ComposedSchema();
        body.addAllOfItem(new Schema<>().$ref("#/components/schemas/ProblemDetailResponse"));
        body.addAllOfItem(fields);
        String description = errors.stream().map(error -> error.name() + "：" + error.title()).collect(Collectors.joining("；"));
        var response = new ApiResponse().description(description).content(new Content()
                .addMediaType("application/problem+json", new MediaType().schema(body)));
        commonHeaders(response);
        if (status == 401) response.addHeaderObject("WWW-Authenticate", new Header().schema(new StringSchema()._enum(List.of("Bearer"))));
        if (status == 429 || errors.contains(ErrorCode.REQUEST_IN_PROGRESS)) {
            response.addHeaderObject("Retry-After", new Header().schema(new IntegerSchema().minimum(BigDecimal.ONE)));
        }
        return response;
    }

    private void commonHeaders(ApiResponse response) {
        if (response.getHeaders() == null) response.setHeaders(new LinkedHashMap<>());
        response.getHeaders().putIfAbsent("X-Request-Id", new Header().schema(new StringSchema()));
        response.getHeaders().putIfAbsent("Cache-Control", new Header().schema(new StringSchema()._enum(List.of("no-store"))));
    }
}
