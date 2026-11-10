package com.freecontent.community.content.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.dto.WorkspacePage;
import com.freecontent.community.content.service.ContentWorkspaceService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ProblemDetailResponse;
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
import static com.freecontent.community.common.error.ErrorCode.MALFORMED_REQUEST;
import static com.freecontent.community.common.error.ErrorCode.RESOURCE_NOT_FOUND;

@RestController
@RequestMapping("/api/v1/creator/contents")
@Tag(name = "创作中心")
public class ContentWorkspaceController {
    private static final Set<String> LIST_PARAMETERS = Set.of("page","pageSize","lifecycle","publicationStatus","kind");
    private final ContentWorkspaceService service;
    public ContentWorkspaceController(ContentWorkspaceService service) { this.service = service; }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listMyContents",summary = "查询本人创作及回收站列表",
            description = "默认本人ACTIVE内容；DELETED读取回收站，按更新时间和ID倒序",security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",description = "本人工作区分页列表")
    public ApiResponse<WorkspacePage> listMyContents(
            @Parameter(schema = @Schema(type = "integer",minimum = "1",defaultValue = "1")) @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer",minimum = "1",maximum = "100",defaultValue = "20")) @RequestParam(required = false) String pageSize,
            @Parameter(schema = @Schema(allowableValues = {"ACTIVE","DELETED"},defaultValue = "ACTIVE")) @RequestParam(required = false) String lifecycle,
            @Parameter(schema = @Schema(allowableValues = {"UNPUBLISHED","PUBLISHED","WITHDRAWN","TAKEN_DOWN"})) @RequestParam(required = false) String publicationStatus,
            @Parameter(schema = @Schema(allowableValues = {"ARTICLE","VIDEO"})) @RequestParam(required = false) String kind,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal,HttpServletRequest request) {
        validateQuery(request,LIST_PARAMETERS);
        return ApiResponse.of(service.listMyContents(page,pageSize,lifecycle,publicationStatus,kind,principal,request.getRemoteAddr()),MDC.get(RequestIdFilter.MDC_KEY));
    }

    @GetMapping(value = "/{contentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getMyContentWorkspace", summary = "读取本人内容工作区",
            description = "包含本人已删除的内容，管理角色不能扩大本人范围", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(RESOURCE_NOT_FOUND)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "当前内容工作区")
    public ApiResponse<ContentWorkspace> getMyWorkspace(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String contentId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        validateQuery(request,Set.of());
        return ApiResponse.of(service.getMyWorkspace(contentId, principal, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }
    private void validateQuery(HttpServletRequest request,Set<String> allowed) {
        for (var entry : request.getParameterMap().entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1) {
                throw new BusinessException(MALFORMED_REQUEST,"查询参数未定义或重复",null,null,
                        List.of(ProblemDetailResponse.Violation.query(entry.getKey(),"参数未定义或重复")));
            }
        }
    }
}
