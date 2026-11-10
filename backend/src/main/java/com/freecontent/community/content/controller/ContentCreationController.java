package com.freecontent.community.content.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.service.ContentCreationService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ProblemDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;
import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/creator/contents")
@Tag(name = "创作中心")
public class ContentCreationController {
    private final ContentCreationService service;
    public ContentCreationController(ContentCreationService service) { this.service = service; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createContent", summary = "创建内容及首个草稿", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({RESOURCE_NOT_FOUND, EMAIL_VERIFICATION_REQUIRED, IDEMPOTENCY_KEY_REUSED, REQUEST_IN_PROGRESS,
            PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "内容与首个草稿已创建",
            headers = @Header(name = "Location", description = "/api/v1/creator/contents/{contentId}", schema = @Schema(type = "string")))
    public ResponseEntity<ApiResponse<ContentWorkspace>> create(@Valid @RequestBody CreateContentRequest body,
            @Parameter(schema = @Schema(minLength = 16, maxLength = 128)) @RequestHeader("Idempotency-Key") String key,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            String field = request.getParameterMap().keySet().iterator().next();
            throw new BusinessException(MALFORMED_REQUEST, "内容创建不接受查询参数", null, null,
                    List.of(ProblemDetailResponse.Violation.query(field, "未定义的查询参数")));
        }
        var result = service.create(body, key, principal, request.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/creator/contents/" + result.id()))
                .body(ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY)));
    }
}
