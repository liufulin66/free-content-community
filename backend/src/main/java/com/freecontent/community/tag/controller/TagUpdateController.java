package com.freecontent.community.tag.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.tag.dto.TagUpdateRequest;
import com.freecontent.community.tag.dto.TagView;
import com.freecontent.community.tag.service.TagUpdateService;
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

import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/admin/tags")
@Tag(name = "标签管理")
public class TagUpdateController {
    private final TagUpdateService service;

    public TagUpdateController(TagUpdateService service) { this.service = service; }

    @PatchMapping(value = "/{tagId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateTag", summary = "修改或停用标签",
            description = "需要ADMIN对应的tag:manage权限；停用保留标签，使用expectedVersion防止并发覆盖", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({RESOURCE_NOT_FOUND, RESOURCE_VERSION_CONFLICT, TAG_NAME_CONFLICT, PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "标签当前管理信息")
    public ApiResponse<TagView> update(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String tagId,
            @Valid @RequestBody TagUpdateRequest patch,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        return ApiResponse.of(service.update(tagId, patch, principal, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }
}
