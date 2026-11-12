package com.freecontent.community.content.review.controller;

import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.content.review.dto.SubmitReviewRequest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.review.service.ContentReviewSubmissionService;
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
import java.util.Collections;
import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/creator/contents")
@Tag(name = "审核流程")
public class ContentReviewSubmissionController {
    private final ContentReviewSubmissionService service;
    public ContentReviewSubmissionController(ContentReviewSubmissionService service) { this.service = service; }

    @PostMapping(value = "/{contentId}/reviews", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "submitContentReview", summary = "提交当前草稿审核", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({RESOURCE_NOT_FOUND, CONTENT_VERSION_CONFLICT, DRAFT_NOT_EDITABLE, REVIEW_ALREADY_PENDING,
            MEDIA_NOT_READY, CONTENT_NOT_EDITABLE, IDEMPOTENCY_KEY_REUSED, REQUEST_IN_PROGRESS, PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "审核申请已创建",
            headers = @Header(name = "Location", description = "/api/v1/creator/contents/{contentId}/reviews/{reviewId}", schema = @Schema(type = "string")))
    public ResponseEntity<ApiResponse<ReviewResult>> submit(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String contentId,
            @Valid @RequestBody SubmitReviewRequest input,
            @Parameter(schema = @Schema(minLength = 16, maxLength = 128)) @RequestHeader("Idempotency-Key") String key,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        if (Collections.list(request.getHeaders("Idempotency-Key")).size() != 1)
            throw new BusinessException(MALFORMED_REQUEST, "幂等请求头不能重复", null, null,
                    List.of(new ProblemDetailResponse.Violation("header", "Idempotency-Key", "只能提供一个幂等键")));
        if (!request.getParameterMap().isEmpty()) {
            String field = request.getParameterMap().keySet().iterator().next();
            throw new BusinessException(MALFORMED_REQUEST, "提交审核不接受查询参数", null, null,
                    List.of(ProblemDetailResponse.Violation.query(field, "未定义的查询参数")));
        }
        var result = service.submit(contentId, input, key, principal, request.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/creator/contents/" + contentId + "/reviews/" + result.review().id()))
                .body(ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY)));
    }
}
