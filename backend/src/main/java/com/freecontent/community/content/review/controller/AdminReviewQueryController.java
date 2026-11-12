package com.freecontent.community.content.review.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.review.dto.ReviewPage;
import com.freecontent.community.content.review.dto.AdminReviewDetail;
import com.freecontent.community.content.review.service.AdminReviewQueryService;
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
import java.util.Set;

@RestController
@RequestMapping("/api/v1/admin/reviews")
@Tag(name = "运营审核")
public class AdminReviewQueryController {
    private static final Set<String> PARAMETERS = Set.of("page", "pageSize", "state", "purpose");
    private final AdminReviewQueryService service;
    public AdminReviewQueryController(AdminReviewQueryService service) { this.service = service; }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listAdminReviews", summary = "查询审核待办和历史", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "审核待办或历史分页列表")
    public ApiResponse<ReviewPage> listAdminReviews(
            @Parameter(schema = @Schema(type = "integer", minimum = "1", defaultValue = "1")) @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20")) @RequestParam(required = false) String pageSize,
            @Parameter(schema = @Schema(allowableValues = {"PENDING", "APPROVED", "REJECTED", "CANCELLED", "INVALIDATED"}, defaultValue = "PENDING")) @RequestParam(required = false) String state,
            @Parameter(schema = @Schema(allowableValues = {"INITIAL", "UPDATE", "REMEDIATION"})) @RequestParam(required = false) String purpose,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        validateQuery(request, PARAMETERS);
        return ApiResponse.of(service.list(page, pageSize, state, purpose, principal, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }

    @GetMapping(value = "/{reviewId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getAdminReview", summary = "读取审核快照与当前版本", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(ErrorCode.RESOURCE_NOT_FOUND)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "指定审核的冻结修订及内容当前工作区")
    public ApiResponse<AdminReviewDetail> getAdminReview(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String reviewId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        validateQuery(request, Set.of());
        return ApiResponse.of(service.get(reviewId, principal, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }

    private void validateQuery(HttpServletRequest request, Set<String> allowed) {
        for (var entry : request.getParameterMap().entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1)
                throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "查询参数未定义或重复", null, null,
                        List.of(ProblemDetailResponse.Violation.query(entry.getKey(), "参数未定义或重复")));
        }
    }
}
