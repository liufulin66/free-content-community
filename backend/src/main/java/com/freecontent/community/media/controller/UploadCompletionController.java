package com.freecontent.community.media.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.media.config.UploadCompletionProperties;
import com.freecontent.community.media.dto.*;
import com.freecontent.community.media.service.UploadCompletionService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/media/uploads")
@Tag(name = "文件与媒体")
public class UploadCompletionController {
    private final UploadCompletionService service;
    private final UploadCompletionProperties properties;
    public UploadCompletionController(UploadCompletionService service, UploadCompletionProperties properties) {
        this.service = service; this.properties = properties;
    }

    @PostMapping(value = "/{uploadId}/completion", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "completeMyUpload", summary = "确认上传完成并申请媒体校验处理", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({RESOURCE_NOT_FOUND, UPLOAD_STATE_CONFLICT, IDEMPOTENCY_KEY_REUSED,
            REQUEST_IN_PROGRESS, PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "已受理，素材尚未READY", headers = {
            @Header(name = "Location", schema = @Schema(type = "string")),
            @Header(name = "Retry-After", schema = @Schema(type = "integer", minimum = "1"))})
    public ResponseEntity<ApiResponse<TaskReceipt>> complete(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String uploadId,
            @Valid @RequestBody CompleteUploadRequest request,
            @Parameter(schema = @Schema(minLength = 16, maxLength = 128)) @RequestHeader("Idempotency-Key") String key,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest servletRequest) {
        TaskReceipt receipt = service.complete(uploadId, request, key, principal, servletRequest.getRemoteAddr());
        return ResponseEntity.accepted().location(URI.create(receipt.statusUrl()))
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(properties.retryAfterSeconds()))
                .body(ApiResponse.of(receipt, MDC.get(RequestIdFilter.MDC_KEY)));
    }
}
