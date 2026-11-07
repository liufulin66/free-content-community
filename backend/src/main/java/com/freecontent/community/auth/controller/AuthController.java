package com.freecontent.community.auth.controller;

import com.freecontent.community.auth.dto.ChallengeReceipt;
import com.freecontent.community.auth.dto.CreateEmailChallengeRequest;
import com.freecontent.community.auth.dto.LoginRequest;
import com.freecontent.community.auth.dto.LoginResult;
import com.freecontent.community.auth.dto.RegisterRequest;
import com.freecontent.community.auth.dto.ResetPasswordRequest;
import com.freecontent.community.auth.service.EmailChallengeService;
import com.freecontent.community.auth.service.LoginService;
import com.freecontent.community.auth.service.LogoutService;
import com.freecontent.community.auth.service.RegistrationService;
import com.freecontent.community.auth.service.PasswordResetService;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.user.dto.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 认证模块的HTTP入口；验证码、注册、登录流程分别由对应Service负责。 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证")
public class AuthController {
    private final EmailChallengeService emailChallengeService;
    private final RegistrationService registrationService;
    private final LoginService loginService;
    private final LogoutService logoutService;
    private final PasswordResetService passwordResetService;

    public AuthController(EmailChallengeService emailChallengeService, RegistrationService registrationService,
                          LoginService loginService, LogoutService logoutService, PasswordResetService passwordResetService) {
        this.emailChallengeService = emailChallengeService;
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.logoutService = logoutService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping(value = "/email-challenges", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "createEmailChallenge", summary = "发送邮箱验证码")
    public ResponseEntity<ApiResponse<ChallengeReceipt>> createEmailChallenge(
            @Valid @RequestBody CreateEmailChallengeRequest request, HttpServletRequest servletRequest) {
        // 使用连接来源，默认不信任客户端可伪造的X-Forwarded-For。
        ChallengeReceipt receipt = emailChallengeService.create(request, servletRequest.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.of(receipt, MDC.get(RequestIdFilter.MDC_KEY)));
    }

    @PostMapping(value = "/registrations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "registerAccount", summary = "邮箱验证码注册")
    public ResponseEntity<ApiResponse<CurrentUser>> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        CurrentUser user = registrationService.register(request, servletRequest.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(user, MDC.get(RequestIdFilter.MDC_KEY)));
    }

    @PostMapping(value = "/sessions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createSession", summary = "密码登录并创建会话")
    public ResponseEntity<ApiResponse<LoginResult>> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        LoginResult result = loginService.login(request, servletRequest.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(result, MDC.get(RequestIdFilter.MDC_KEY)));
    }

    @DeleteMapping("/sessions/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteCurrentSession", summary = "退出当前会话",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<Void> logout(@Parameter(hidden = true) @AuthenticationPrincipal SessionPrincipal currentSession) {
        logoutService.logoutCurrentSession(currentSession);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/password-resets", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "resetPassword", summary = "验证码重置密码")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                             HttpServletRequest servletRequest) {
        passwordResetService.reset(request, servletRequest.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
