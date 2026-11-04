package com.freecontent.community.common.error;

import com.freecontent.community.common.api.RequestIdFilter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.stream.Stream;

/**
 * 所有失败响应统一转换成契约规定的 application/problem+json。
 *
 * <p>这里同时负责：HTTP 状态与 Problem.status 一致、Cache-Control: no-store、
 * 401 的 WWW-Authenticate、429/REQUEST_IN_PROGRESS 的 Retry-After。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetailResponse> handleBusiness(BusinessException ex) {
        return problem(ex.errorCode(), ex.getMessage(), ex.violations(), ex.currentVersion(), ex.retryAfterSeconds());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetailResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ProblemDetailResponse.Violation> fieldViolations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> ProblemDetailResponse.Violation.body(error.getField(), safeMessage(error.getDefaultMessage())))
                .toList();
        List<ProblemDetailResponse.Violation> globalViolations = ex.getBindingResult().getGlobalErrors().stream()
                .map(error -> ProblemDetailResponse.Violation.body("", safeMessage(error.getDefaultMessage())))
                .toList();
        List<ProblemDetailResponse.Violation> violations = Stream.concat(fieldViolations.stream(), globalViolations.stream())
                .toList();
        return problem(ErrorCode.VALIDATION_ERROR, "请求参数校验失败", violations, null, null);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetailResponse> handleMethodValidation(HandlerMethodValidationException ex) {
        List<ProblemDetailResponse.Violation> parameterViolations = ex.getParameterValidationResults().stream()
                .flatMap(result -> {
                    String parameter = result.getMethodParameter() == null
                            ? null
                            : result.getMethodParameter().getParameterName();
                    String path = parameter == null ? "parameter" : parameter;
                    return result.getResolvableErrors().stream()
                            .map(error -> ProblemDetailResponse.Violation.body(path, safeMessage(error.getDefaultMessage())));
                })
                .toList();
        List<ProblemDetailResponse.Violation> crossParameterViolations = ex.getCrossParameterValidationResults().stream()
                .map(error -> ProblemDetailResponse.Violation.body("", safeMessage(error.getDefaultMessage())))
                .toList();
        List<ProblemDetailResponse.Violation> violations = Stream.concat(
                parameterViolations.stream(), crossParameterViolations.stream()).toList();
        return problem(ErrorCode.VALIDATION_ERROR, "请求参数校验失败", violations, null, null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetailResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<ProblemDetailResponse.Violation> violations = ex.getConstraintViolations().stream()
                .map(violation -> ProblemDetailResponse.Violation.body(
                        violation.getPropertyPath().toString(), safeMessage(violation.getMessage())))
                .toList();
        return problem(ErrorCode.VALIDATION_ERROR, "请求参数校验失败", violations, null, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetailResponse> handleMalformedBody(HttpMessageNotReadableException ex) {
        return problem(ErrorCode.MALFORMED_REQUEST, "请求体不是合法的 JSON 或字段类型不正确", List.of(), null, null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetailResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        return problem(ErrorCode.MALFORMED_REQUEST, "缺少必填参数",
                List.of(ProblemDetailResponse.Violation.query(ex.getParameterName(), "缺少必填参数")), null, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetailResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(ErrorCode.MALFORMED_REQUEST, "参数格式不正确",
                List.of(ProblemDetailResponse.Violation.query(ex.getName(), "参数格式不正确")), null, null);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ProblemDetailResponse> handleNotFound(Exception ex) {
        return problem(ErrorCode.RESOURCE_NOT_FOUND, "请求的资源不存在", List.of(), null, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetailResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return problem(ErrorCode.METHOD_NOT_ALLOWED, "请求方法不支持", List.of(), null, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetailResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return problem(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "不支持的媒体类型", List.of(), null, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetailResponse> handlePayloadTooLarge(MaxUploadSizeExceededException ex) {
        return problem(ErrorCode.PAYLOAD_TOO_LARGE, "请求内容超出大小限制", List.of(), null, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetailResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception, requestId={}", currentRequestId(), ex);
        return problem(ErrorCode.INTERNAL_ERROR, "服务器内部错误", List.of(), null, null);
    }

    private ResponseEntity<ProblemDetailResponse> problem(ErrorCode errorCode,
                                                          String detail,
                                                          List<ProblemDetailResponse.Violation> violations,
                                                          Integer currentVersion,
                                                          Long retryAfterSeconds) {
        String requestId = currentRequestId();
        String safeDetail = (detail == null || detail.isBlank()) ? errorCode.title() : detail;

        ProblemDetailResponse body = new ProblemDetailResponse(
                errorCode.type(),
                errorCode.title(),
                errorCode.statusCode(),
                safeDetail,
                "urn:request:" + requestId,
                errorCode.name(),
                requestId,
                violations == null ? List.of() : violations,
                currentVersion
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/problem+json"));
        headers.setCacheControl("no-store");
        if (errorCode.statusCode() == HttpStatus.UNAUTHORIZED.value()) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        if (retryAfterSeconds != null) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        }

        return new ResponseEntity<>(body, headers, HttpStatus.valueOf(errorCode.statusCode()));
    }

    private String currentRequestId() {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return requestId == null ? "req_unknown" : requestId;
    }

    private String safeMessage(String message) {
        return (message == null || message.isBlank()) ? "参数不合法" : message;
    }
}
