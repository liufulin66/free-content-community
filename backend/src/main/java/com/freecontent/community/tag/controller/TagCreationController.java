package com.freecontent.community.tag.controller;

import java.net.URI;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import static com.freecontent.community.common.error.ErrorCode.IDEMPOTENCY_KEY_REUSED;
import static com.freecontent.community.common.error.ErrorCode.PAYLOAD_TOO_LARGE;
import static com.freecontent.community.common.error.ErrorCode.REQUEST_IN_PROGRESS;
import static com.freecontent.community.common.error.ErrorCode.TAG_NAME_CONFLICT;
import static com.freecontent.community.common.error.ErrorCode.UNSUPPORTED_MEDIA_TYPE;
import com.freecontent.community.tag.dto.TagView;
import com.freecontent.community.tag.dto.TagWriteRequest;
import com.freecontent.community.tag.service.TagCreationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/tags")
@Tag(name = "标签管理")
public class TagCreationController {

    private final TagCreationService service;

    public TagCreationController(TagCreationService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createTag", summary = "创建标签", description = "需要ADMIN对应的tag:manage权限；名称去除首尾空白并忽略大小写判重", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({TAG_NAME_CONFLICT, IDEMPOTENCY_KEY_REUSED, REQUEST_IN_PROGRESS,
        PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE})
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "标签已创建",
            headers = @Header(name = "Location", schema = @Schema(type = "string")))
    public ResponseEntity<ApiResponse<TagView>> create(@Valid @RequestBody TagWriteRequest request,
            @Parameter(schema = @Schema(minLength = 16, maxLength = 128)) @RequestHeader("Idempotency-Key") String key,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest servletRequest) {
        TagView result = service.create(request, key, principal, servletRequest.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/admin/tags/" + result.id()))
                .body(ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY)));
    }
}
