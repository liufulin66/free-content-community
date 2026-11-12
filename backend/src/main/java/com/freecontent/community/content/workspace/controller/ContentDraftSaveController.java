package com.freecontent.community.content.workspace.controller;

import com.freecontent.community.content.workspace.dto.EditDraftRequest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.workspace.service.ContentDraftSaveService;
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
import java.util.List;
import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/creator/contents")
@Tag(name = "创作中心")
public class ContentDraftSaveController {
    private final ContentDraftSaveService service;
    public ContentDraftSaveController(ContentDraftSaveService service) { this.service = service; }
    @PatchMapping(value = "/{contentId}/draft",consumes = MediaType.APPLICATION_JSON_VALUE,produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "editContentDraft",summary = "保存当前可编辑草稿",security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({RESOURCE_NOT_FOUND,CONTENT_VERSION_CONFLICT,DRAFT_NOT_EDITABLE,CONTENT_NOT_EDITABLE,PAYLOAD_TOO_LARGE,UNSUPPORTED_MEDIA_TYPE})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",description = "保存后的内容工作区")
    public ApiResponse<ContentWorkspace> save(
            @Parameter(schema = @Schema(minLength = 1,maxLength = 64,pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String contentId,
            @Valid @RequestBody EditDraftRequest patch,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal,HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            String name = request.getParameterMap().keySet().iterator().next();
            throw new BusinessException(MALFORMED_REQUEST,"草稿保存不接受查询参数",null,null,List.of(ProblemDetailResponse.Violation.query(name,"未定义的查询参数")));
        }
        return ApiResponse.of(service.save(contentId,patch,principal,request.getRemoteAddr()),MDC.get(RequestIdFilter.MDC_KEY));
    }
}
