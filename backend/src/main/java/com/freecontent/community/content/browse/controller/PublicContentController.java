package com.freecontent.community.content.browse.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.dto.PublicContentView;
import com.freecontent.community.content.browse.dto.ContentPageView;
import com.freecontent.community.content.browse.service.PublicContentService;
import com.freecontent.community.content.browse.service.PublicContentListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/contents")
@Tag(name = "内容浏览")
public class PublicContentController {
    private final PublicContentService service;
    private final PublicContentListService lists;
    public PublicContentController(PublicContentService service, PublicContentListService lists) { this.service = service; this.lists = lists; }

    @GetMapping(produces = "application/json")
    @Operation(operationId = "listPublicContents", summary = "浏览公开内容列表",
            description = "允许游客；按首次发布时间和ID倒序，只返回当前公开的发布修订。游标绑定筛选、调用者和快照上界。")
    @ApiErrorResponses(value = {MALFORMED_REQUEST, AUTHENTICATION_REQUIRED, CURSOR_INVALID, CURSOR_EXPIRED,
            VALIDATION_ERROR, RATE_LIMITED, INTERNAL_ERROR, DEPENDENCY_UNAVAILABLE}, includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "公开内容连续浏览")
    public ApiResponse<ContentPageView> listPublishedContents(
            @Parameter(schema = @Schema(maxLength = 4096)) @RequestParam(required = false) String cursor,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "50", defaultValue = "20")) @RequestParam(required = false) String limit,
            @Parameter(schema = @Schema(allowableValues = {"ARTICLE", "VIDEO"})) @RequestParam(required = false) String kind,
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @RequestParam(required = false) String tagId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal viewer, HttpServletRequest request) {
        var allowed = java.util.Set.of("cursor", "limit", "kind", "tagId");
        for (var entry : request.getParameterMap().entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1) throw new BusinessException(MALFORMED_REQUEST,
                    "查询参数未定义或重复", null, null, List.of(ProblemDetailResponse.Violation.query(entry.getKey(), "参数未定义或重复")));
        }
        return ApiResponse.of(lists.listPublishedContents(cursor, limit, kind, tagId, viewer, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }

    @GetMapping(value = "/{contentId}", produces = "application/json")
    @Operation(operationId = "getPublicContent", summary = "查看已发布内容详情",
            description = "允许游客；携带Token时必须验证。只读取已发布修订，读取不增加浏览量。")
    @ApiErrorResponses(value = {MALFORMED_REQUEST, AUTHENTICATION_REQUIRED, RESOURCE_NOT_FOUND,
            VALIDATION_ERROR, RATE_LIMITED, INTERNAL_ERROR, DEPENDENCY_UNAVAILABLE}, includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "已发布内容详情")
    public ApiResponse<PublicContentView> getPublishedContent(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String contentId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal viewer, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new BusinessException(MALFORMED_REQUEST,
                "公开详情不接受查询参数", null, null, request.getParameterMap().keySet().stream()
                .map(name -> ProblemDetailResponse.Violation.query(name, "未定义的查询参数")).toList());
        return ApiResponse.of(service.getPublishedContent(contentId, viewer, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }
}
