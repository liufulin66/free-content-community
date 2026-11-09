package com.freecontent.community.user.controller;

import com.freecontent.community.user.dto.ChangePasswordRequest;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.dto.UpdateProfileRequest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.user.service.PasswordChangeService;
import com.freecontent.community.user.service.CurrentUserService;
import com.freecontent.community.user.service.UserProfileService;
import com.freecontent.community.common.api.ApiErrorResponses;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.freecontent.community.common.error.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "账号")
public class MeController {
    private final PasswordChangeService passwords;
    private final CurrentUserService currentUsers;
    private final UserProfileService profiles;

    public MeController(PasswordChangeService passwords, CurrentUserService currentUsers, UserProfileService profiles) {
        this.passwords = passwords;
        this.currentUsers = currentUsers;
        this.profiles = profiles;
    }

    @GetMapping
    @Operation(operationId = "getCurrentUser", summary = "获取当前用户资料",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses
    public ApiResponse<CurrentUser> getCurrentUser(
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal currentSession) {
        return ApiResponse.of(currentUsers.getCurrentUser(currentSession), MDC.get(RequestIdFilter.MDC_KEY));
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateCurrentUser", summary = "修改当前用户资料",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({RESOURCE_NOT_FOUND, RESOURCE_VERSION_CONFLICT, MEDIA_NOT_READY,
            PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE})
    public ApiResponse<CurrentUser> updateProfile(@Valid @RequestBody UpdateProfileRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal currentSession,
            HttpServletRequest servletRequest) {
        return ApiResponse.of(profiles.update(request, currentSession, servletRequest.getRemoteAddr()),
                MDC.get(RequestIdFilter.MDC_KEY));
    }

    @PutMapping(value = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "changePassword", summary = "修改当前账号密码",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiErrorResponses({AUTHENTICATION_FAILED, PAYLOAD_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE})
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal currentSession,
            HttpServletRequest servletRequest) {
        passwords.change(request, currentSession, servletRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
