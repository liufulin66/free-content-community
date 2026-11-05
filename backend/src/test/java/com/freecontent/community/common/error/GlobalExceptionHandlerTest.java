package com.freecontent.community.common.error;

import com.freecontent.community.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 只启动 Web 切片，不连 MySQL/Redis/RabbitMQ。
 * 验证错误响应与契约的字段、状态码和关键响应头一致。
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@Import({GlobalExceptionHandlerTest.TestController.class, GlobalExceptionHandlerTest.ErrorTestSecurity.class})
class GlobalExceptionHandlerTest {
    // 本切片只验证MVC异常转换，生产Bearer链由SessionEndpointTest单独覆盖。
    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class ErrorTestSecurity {
        @Bean
        SecurityFilterChain errorTestSecurity(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validationErrorMatchesContractShape() throws Exception {
        mockMvc.perform(post("/api/v1/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].location").value("body"))
                .andExpect(jsonPath("$.violations[0].path").value("name"))
                .andExpect(jsonPath("$.violations[0].reason").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void malformedJsonReturnsMalformedRequest() throws Exception {
        mockMvc.perform(post("/api/v1/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void versionConflictExposesCurrentVersion() throws Exception {
        mockMvc.perform(get("/api/v1/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("CONTENT_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(8))
                .andExpect(jsonPath("$.violations").isArray());
    }

    @Test
    void authenticationRequiredSetsWwwAuthenticate() throws Exception {
        mockMvc.perform(get("/api/v1/test/auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void rateLimitedSetsRetryAfter() throws Exception {
        mockMvc.perform(get("/api/v1/test/rate-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @RestController
    static class TestController {

        @PostMapping("/api/v1/test/validate")
        ApiResponse<String> validate(@Valid @RequestBody TestRequest request) {
            return ApiResponse.of("ok", "req_test");
        }

        @GetMapping("/api/v1/test/conflict")
        void conflict() {
            throw new BusinessException(ErrorCode.CONTENT_VERSION_CONFLICT, "内容已被其他操作更新", 8);
        }

        @GetMapping("/api/v1/test/auth")
        void auth() {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "需要登录");
        }

        @GetMapping("/api/v1/test/rate-limit")
        void rateLimit() {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "请求过于频繁", null, 30L, List.of());
        }
    }

    record TestRequest(@NotBlank String name) {
    }
}
