package com.freecontent.community.content.review.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.content.review.service.ContentReviewQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/creator/contents")
@Tag(name = "审核流程")
public class ContentReviewQueryController {
    private final ContentReviewQueryService service;
    public ContentReviewQueryController(ContentReviewQueryService service) { this.service = service; }

    @GetMapping(value = "/{contentId}/reviews/{reviewId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getMyReview", summary = "查看本人审核进度与反馈", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(RESOURCE_NOT_FOUND)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "指定审核的当前状态与反馈及内容当前版本")
    public ApiResponse<ReviewResult> getMyReview(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String contentId,
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String reviewId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            String field = request.getParameterMap().keySet().iterator().next();
            throw new BusinessException(MALFORMED_REQUEST, "审核查询不接受查询参数", null, null,
                    List.of(ProblemDetailResponse.Violation.query(field, "未定义的查询参数")));
        }
        return ApiResponse.of(service.getMyReview(contentId, reviewId, principal, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }
}
