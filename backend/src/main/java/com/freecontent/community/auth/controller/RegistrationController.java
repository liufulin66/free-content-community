package com.freecontent.community.auth.controller;

import com.freecontent.community.auth.dto.RegisterRequest;
import com.freecontent.community.auth.service.RegistrationService;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import com.freecontent.community.user.dto.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证")
public class RegistrationController {
    private final RegistrationService service;

    public RegistrationController(RegistrationService service) {
        this.service = service;
    }

    @PostMapping(value = "/registrations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "registerAccount", summary = "邮箱验证码注册")
    public ResponseEntity<ApiResponse<CurrentUser>> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        CurrentUser user = service.register(request, servletRequest.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(user, MDC.get(RequestIdFilter.MDC_KEY)));
    }
}
