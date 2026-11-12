package com.freecontent.community.content.review.controller;

import java.util.List;

import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import static com.freecontent.community.common.error.ErrorCode.CONTENT_VERSION_CONFLICT;
import static com.freecontent.community.common.error.ErrorCode.MALFORMED_REQUEST;
import static com.freecontent.community.common.error.ErrorCode.PAYLOAD_TOO_LARGE;
import static com.freecontent.community.common.error.ErrorCode.RESOURCE_NOT_FOUND;
import static com.freecontent.community.common.error.ErrorCode.REVIEW_NOT_PENDING;
import static com.freecontent.community.common.error.ErrorCode.REVIEW_VERSION_CONFLICT;
import static com.freecontent.community.common.error.ErrorCode.UNSUPPORTED_MEDIA_TYPE;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.review.dto.CancelReviewRequest;
import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.content.review.service.ReviewCancellationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/creator/contents")
@Tag(name = "审核流程")
public class ReviewCancellationController {

    private final ReviewCancellationService service;

    public ReviewCancellationController(ReviewCancellationService service) {
        this.service = service;
    }

    @PutMapping(value = "/{contentId}/reviews/{reviewId}/cancellation", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "cancelMyReview", summary = "撤销本人待审核申请", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({RESOURCE_NOT_FOUND, CONTENT_VERSION_CONFLICT, REVIEW_VERSION_CONFLICT, REVIEW_NOT_PENDING, PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "撤销后的审核及内容当前版本")
    public ApiResponse<ReviewResult> cancel(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String contentId,
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String reviewId,
            @Valid @RequestBody CancelReviewRequest input,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            String field = request.getParameterMap().keySet().iterator().next();
            throw new BusinessException(MALFORMED_REQUEST, "撤销审核不接受查询参数", null, null, List.of(ProblemDetailResponse.Violation.query(field, "未定义的查询参数")));
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return ApiResponse.of(service.cancel(contentId, reviewId, input, principal, request.getRemoteAddr(), requestId), requestId);
    }
}
