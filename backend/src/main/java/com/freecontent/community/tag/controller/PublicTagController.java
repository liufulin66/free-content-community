package com.freecontent.community.tag.controller;

import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.tag.dto.TagListView;
import com.freecontent.community.tag.service.TagQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/tags")
@Tag(name = "标签")
public class PublicTagController {
    private final TagQueryService service;

    public PublicTagController(TagQueryService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listActiveTags", summary = "查询有效标签", description = "公开接口；返回全部ACTIVE标签，按名称及ID升序")
    @ApiErrorResponses(value = {MALFORMED_REQUEST, VALIDATION_ERROR, RATE_LIMITED, INTERNAL_ERROR, DEPENDENCY_UNAVAILABLE}, includeCommon = false)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "当前有效标签")
    public ApiResponse<TagListView> listActiveTags(HttpServletRequest request) {
        TagListView result = service.listActiveTags(request.getRemoteAddr(), request.getParameterMap().keySet());
        return ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY));
    }
}
