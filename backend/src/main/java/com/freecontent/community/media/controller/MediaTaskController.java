package com.freecontent.community.media.controller;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.*;
import com.freecontent.community.media.dto.MediaTaskView;
import com.freecontent.community.media.service.MediaTaskQueryService;
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
@RequestMapping("/api/v1/tasks")
@Tag(name = "异步任务")
public class MediaTaskController {
    private final MediaTaskQueryService service;
    public MediaTaskController(MediaTaskQueryService service) { this.service = service; }

    @GetMapping("/{taskId}")
    @Operation(operationId = "getMyTask", summary = "查询本人的媒体处理任务", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses(RESOURCE_NOT_FOUND)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "本人媒体处理任务")
    public ApiResponse<MediaTaskView> getMyTask(
            @Parameter(schema = @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) @PathVariable String taskId,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        return ApiResponse.of(service.getMyTask(taskId, principal, request.getRemoteAddr()), MDC.get(RequestIdFilter.MDC_KEY));
    }
}
