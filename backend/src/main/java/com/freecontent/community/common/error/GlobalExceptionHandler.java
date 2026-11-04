package com.freecontent.community.common.error;

import com.freecontent.community.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetailResponse> handleValidation(MethodArgumentNotValidException ex,
                                                                   HttpServletRequest request) {
        List<ProblemDetailResponse.Violation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ProblemDetailResponse.Violation(error.getField(), error.getDefaultMessage()))
                .toList();

        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED",
                "请求参数校验失败", request, violations);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetailResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception, requestId={}", currentRequestId(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "服务器内部错误", request, List.of());
    }

    private ResponseEntity<ProblemDetailResponse> problem(HttpStatus status,
                                                          String code,
                                                          String detail,
                                                          HttpServletRequest request,
                                                          List<ProblemDetailResponse.Violation> violations) {
        String requestId = currentRequestId();
        ProblemDetailResponse body = new ProblemDetailResponse(
                "urn:free-content:problem:" + code.toLowerCase().replace('_', '-'),
                detail,
                status.value(),
                detail,
                "urn:request:" + requestId,
                code,
                requestId,
                violations
        );

        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType("application/problem+json"))
                .body(body);
    }

    private String currentRequestId() {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return requestId == null ? "req_unknown" : requestId;
    }
}
