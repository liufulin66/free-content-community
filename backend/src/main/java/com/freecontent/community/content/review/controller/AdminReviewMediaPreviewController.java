package com.freecontent.community.content.review.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.review.service.AdminReviewMediaPreviewService;
import com.freecontent.community.media.dto.MediaAccess;
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
@RequestMapping("/api/v1/admin/reviews")
@Tag(name = "运营审核")
public class AdminReviewMediaPreviewController {
    private final AdminReviewMediaPreviewService service;
    public AdminReviewMediaPreviewController(AdminReviewMediaPreviewService service) { this.service = service; }

    @GetMapping(value = "/{reviewId}/media/{assetId}/access", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getReviewMediaAccess", summary = "预览审核修订关联媒体", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({RESOURCE_NOT_FOUND, MEDIA_NOT_READY})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "审核修订媒体的短时私有读取地址")
    public ApiResponse<MediaAccess> getAccess(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String reviewId,
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String assetId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            String field = request.getParameterMap().keySet().iterator().next();
            throw new BusinessException(MALFORMED_REQUEST, "审核媒体预览不接受查询参数", null, null,
                    List.of(ProblemDetailResponse.Violation.query(field, "未定义的查询参数")));
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return ApiResponse.of(service.getAccess(reviewId, assetId, principal, request.getRemoteAddr(), requestId), requestId);
    }
}
