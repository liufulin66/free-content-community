package com.freecontent.community.media.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.media.dto.MediaTaskView;
import com.freecontent.community.media.service.MediaTaskQueryService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "异步任务")
public class MediaTaskController {
    private final MediaTaskQueryService service;
    public MediaTaskController(MediaTaskQueryService service) { this.service = service; }

    @GetMapping("/{taskId}")
    @Operation(operationId = "getMyTask", summary = "查询本人的媒体处理任务", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "本人媒体处理任务", headers = {
                @Header(name = "X-Request-Id", schema = @Schema(type = "string")),
                @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store"))}),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST", headers = {
                @Header(name = "X-Request-Id", schema = @Schema(type = "string")),
                @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store"))},
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED", headers = {
                @Header(name = "X-Request-Id", schema = @Schema(type = "string")),
                @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
                @Header(name = "WWW-Authenticate", schema = @Schema(type = "string", allowableValues = "Bearer"))},
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "PERMISSION_DENIED / ACCOUNT_RESTRICTED", headers = {
                @Header(name = "X-Request-Id", schema = @Schema(type = "string")),
                @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store"))},
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND", headers = {
                @Header(name = "X-Request-Id", schema = @Schema(type = "string")),
                @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store"))},
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "VALIDATION_ERROR", headers = {
                @Header(name = "X-Request-Id", schema = @Schema(type = "string")),
                @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store"))},
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "RATE_LIMITED", headers = {
                @Header(name = "X-Request-Id", schema = @Schema(type = "string")),
                @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
                @Header(name = "Retry-After", schema = @Schema(type = "integer", minimum = "1"))},
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", headers = {
                @Header(name = "X-Request-Id", schema = @Schema(type = "string")),
                @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store"))},
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetailResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "DEPENDENCY_UNAVAILABLE", headers = {
                @Header(name = "X-Request-Id", schema = @Schema(type = "string")),
                @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store"))},
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetailResponse.class)))
    })
    public ApiResponse<MediaTaskView> getMyTask(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String taskId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        return ApiResponse.of(service.getMyTask(taskId, principal, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }
}
