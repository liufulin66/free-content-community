package com.freecontent.community.tag.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.tag.dto.TagView;
import com.freecontent.community.tag.dto.TagPageView;
import com.freecontent.community.tag.service.TagQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Set;

import static com.freecontent.community.common.error.ErrorCode.RESOURCE_NOT_FOUND;

@RestController
@RequestMapping("/api/v1/admin/tags")
@Tag(name = "标签管理")
public class TagQueryController {
    private static final Set<String> LIST_QUERY_PARAMETERS = Set.of("page", "pageSize");
    private final TagQueryService service;

    public TagQueryController(TagQueryService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listAdminTags", summary = "查询全部标签",
            description = "需要ADMIN对应的tag:manage权限；包含启用和停用标签，按创建时间及ID倒序分页", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "标签管理分页列表")
    public ApiResponse<TagPageView> listAdminTags(
            @Parameter(schema = @Schema(type = "integer", minimum = "1", defaultValue = "1")) @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20")) @RequestParam(required = false) String pageSize,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        TagQueryParameters.validate(request, LIST_QUERY_PARAMETERS);
        return ApiResponse.of(service.listAdminTags(page, pageSize, principal, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }

    @GetMapping(value = "/{tagId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getAdminTag", summary = "管理员读取指定标签",
            description = "需要ADMIN对应的tag:manage权限；可读取启用和停用标签", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(RESOURCE_NOT_FOUND)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "标签当前管理信息")
    public ApiResponse<TagView> getAdminTag(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String tagId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        TagQueryParameters.validate(request, Set.of());
        return ApiResponse.of(service.getAdminTag(tagId, principal, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }
}
