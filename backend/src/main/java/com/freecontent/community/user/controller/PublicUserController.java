package com.freecontent.community.user.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ProblemDetailResponse;
import com.freecontent.community.user.dto.PublicUserView;
import com.freecontent.community.user.service.PublicUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "公开资料")
public class PublicUserController {
    private final PublicUserService service;

    public PublicUserController(PublicUserService service) {
        this.service = service;
    }

    @GetMapping(value = "/{userId}", produces = "application/json")
    @Operation(operationId = "getPublicUser", summary = "查看公开个人主页",
            description = "允许游客；携带Token时必须验证。仅返回公开资料，不返回邮箱、会话或账号权限。")
    @ApiErrorResponses(value = {MALFORMED_REQUEST, AUTHENTICATION_REQUIRED, RESOURCE_NOT_FOUND,
            VALIDATION_ERROR, RATE_LIMITED, INTERNAL_ERROR, DEPENDENCY_UNAVAILABLE}, includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "公开个人主页")
    public ApiResponse<PublicUserView> getPublicUser(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$"))
            @PathVariable String userId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal viewer,
            HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new BusinessException(MALFORMED_REQUEST, "公开个人主页不接受查询参数", null, null,
                    request.getParameterMap().keySet().stream()
                            .map(name -> ProblemDetailResponse.Violation.query(name, "未定义的查询参数")).toList());
        }
        return ApiResponse.of(service.getPublicUser(userId, viewer, request.getRemoteAddr()),
                MDC.get(RequestIdFilter.MDC_KEY));
    }
}
