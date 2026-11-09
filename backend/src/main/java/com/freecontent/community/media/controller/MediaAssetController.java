package com.freecontent.community.media.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.media.dto.MediaAssetView;
import com.freecontent.community.media.service.MediaAssetQueryService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/media/assets")
@Tag(name = "文件与媒体")
public class MediaAssetController {
    private final MediaAssetQueryService service;
    public MediaAssetController(MediaAssetQueryService service) { this.service = service; }

    @GetMapping("/{assetId}")
    @Operation(operationId = "getMyAsset", summary = "查询本人素材校验结果", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(RESOURCE_NOT_FOUND)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "本人素材元数据")
    public ApiResponse<MediaAssetView> getMyAsset(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String assetId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        return ApiResponse.of(service.getMyAsset(assetId, principal, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }
}
