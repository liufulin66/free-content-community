package com.freecontent.community.auth.controller;

import com.freecontent.community.auth.dto.ChallengeReceipt;
import com.freecontent.community.auth.dto.CreateEmailChallengeRequest;
import com.freecontent.community.auth.service.EmailChallengeService;
import com.freecontent.community.common.api.ApiResponse;
import com.freecontent.community.common.api.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证")
public class EmailChallengeController {

    private final EmailChallengeService service;

    public EmailChallengeController(EmailChallengeService service) {
        this.service = service;
    }

    @PostMapping(value = "/email-challenges", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createEmailChallenge", summary = "发送邮箱验证码")
    public ResponseEntity<ApiResponse<ChallengeReceipt>> create(
            @Valid @RequestBody CreateEmailChallengeRequest request,
            HttpServletRequest servletRequest) {
        // 使用连接来源，默认不信任客户端可伪造的X-Forwarded-For。
        ChallengeReceipt receipt = service.create(request, servletRequest.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.of(receipt, MDC.get(RequestIdFilter.MDC_KEY)));
    }
}
