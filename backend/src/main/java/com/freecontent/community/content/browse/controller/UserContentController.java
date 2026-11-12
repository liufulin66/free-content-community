package com.freecontent.community.content.browse.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.content.browse.dto.ContentPageView;
import com.freecontent.community.content.browse.service.UserContentListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/users/{userId}/contents")
@Tag(name = "公开资料")
public class UserContentController {
    private final UserContentListService service;

    public UserContentController(UserContentListService service) {
        this.service = service;
    }

    @GetMapping(produces = "application/json")
    @Operation(operationId = "listUserContents", summary = "浏览某作者公开内容",
            description = "允许游客；携带Token时必须验证。只返回目标作者当前公开的已审核发布修订。")
    @ApiErrorResponses(value = {MALFORMED_REQUEST, AUTHENTICATION_REQUIRED, RESOURCE_NOT_FOUND, CURSOR_INVALID,
            CURSOR_EXPIRED, VALIDATION_ERROR, RATE_LIMITED, INTERNAL_ERROR, DEPENDENCY_UNAVAILABLE}, includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "作者公开作品连续浏览")
    public ApiResponse<ContentPageView> listUserContents(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String userId,
            @Parameter(schema = @Schema(maxLength = 4096)) @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "50", defaultValue = "20"))
            @RequestParam(required = false) String limit,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal viewer, HttpServletRequest request) {
        var allowed = Set.of("cursor", "limit");
        for (var entry : request.getParameterMap().entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1) {
                throw new BusinessException(MALFORMED_REQUEST, "查询参数未定义或重复", null, null,
                        List.of(ProblemDetailResponse.Violation.query(entry.getKey(), "参数未定义或重复")));
            }
        }
        return ApiResponse.of(service.listUserContents(userId, cursor, limit, viewer, request.getRemoteAddr()),
                MDC.get(RequestIdFilter.MDC_KEY));
    }
}
