package com.freecontent.community.media.controller;

import java.net.URI;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.media.dto.CreateUploadRequest;
import com.freecontent.community.media.dto.UploadPolicy;
import com.freecontent.community.media.dto.UploadSession;
import com.freecontent.community.media.service.UploadPolicyService;
import com.freecontent.community.media.service.UploadService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/media")
@Tag(name = "文件与媒体")
public class MediaController {

    private final UploadPolicyService policies;
    private final UploadService uploads;

    public MediaController(UploadPolicyService policies, UploadService uploads) {
        this.policies = policies;
        this.uploads = uploads;
    }

    @GetMapping("/upload-policy")
    @Operation(operationId = "getUploadPolicy", summary = "获取当前上传限制与签名策略",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ApiResponse<UploadPolicy> getUploadPolicy() {
        return ApiResponse.of(policies.getUploadPolicy(), MDC.get(RequestIdFilter.MDC_KEY));
    }

    @PostMapping(value = "/uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createUpload", summary = "创建私有文件上传会话", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ApiResponse<UploadSession>> createUpload(@Valid @RequestBody CreateUploadRequest request,
            @Parameter(schema = @Schema(minLength = 16, maxLength = 128)) @RequestHeader("Idempotency-Key") String key,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest servletRequest) {
        UploadSession result = uploads.create(request, key, principal, servletRequest.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/media/uploads/" + result.id()))
                .body(ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY)));
    }
}
