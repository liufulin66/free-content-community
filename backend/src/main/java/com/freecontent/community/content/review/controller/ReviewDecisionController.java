package com.freecontent.community.content.review.controller;

import com.freecontent.community.content.review.dto.ReviewDecisionRequest;
import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.review.service.ReviewDecisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/admin/reviews")
@Tag(name = "运营审核")
public class ReviewDecisionController {
    private final ReviewDecisionService service;
    public ReviewDecisionController(ReviewDecisionService service) { this.service = service; }
    @PostMapping(value = "/{reviewId}/decisions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "decideReview", summary = "批准或驳回指定修订", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({RESOURCE_NOT_FOUND, REVIEW_VERSION_CONFLICT, CONTENT_VERSION_CONFLICT, REVIEW_NOT_PENDING, MEDIA_NOT_READY,
            CONTENT_NOT_EDITABLE, IDEMPOTENCY_KEY_REUSED, REQUEST_IN_PROGRESS, PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "审核决定及提交后的内容版本")
    public ApiResponse<ReviewResult> decide(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String reviewId,
            @Valid @RequestBody ReviewDecisionRequest input,
            @Parameter(schema = @Schema(minLength = 16, maxLength = 128)) @RequestHeader("Idempotency-Key") String key,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        if (Collections.list(request.getHeaders("Idempotency-Key")).size() != 1)
            throw new BusinessException(MALFORMED_REQUEST, "幂等请求头不能重复", null, null, List.of(new ProblemDetailResponse.Violation("header", "Idempotency-Key", "只能提供一个幂等键")));
        if (!request.getParameterMap().isEmpty()) {
            String field = request.getParameterMap().keySet().iterator().next();
            throw new BusinessException(MALFORMED_REQUEST, "审核决定不接受查询参数", null, null, List.of(ProblemDetailResponse.Violation.query(field, "未定义的查询参数")));
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return ApiResponse.of(service.decide(reviewId, input, key, principal, request.getRemoteAddr(), requestId), requestId);
    }
}
