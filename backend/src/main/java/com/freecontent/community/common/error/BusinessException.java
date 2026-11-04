package com.freecontent.community.common.error;

import java.util.List;

/**
 * 业务层抛出的可预期错误。
 * 由 GlobalExceptionHandler 统一转换成 RFC 9457 Problem Details，
 * 业务代码不需要自己拼 HTTP 状态码和响应体。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Integer currentVersion;
    private final Long retryAfterSeconds;
    private final List<ProblemDetailResponse.Violation> violations;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null, null, null, List.of());
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, null, null, List.of());
    }

    public BusinessException(ErrorCode errorCode, String detail, Integer currentVersion) {
        this(errorCode, detail, currentVersion, null, List.of());
    }

    public BusinessException(ErrorCode errorCode,
                             String detail,
                             Integer currentVersion,
                             Long retryAfterSeconds,
                             List<ProblemDetailResponse.Violation> violations) {
        super(detail);
        this.errorCode = errorCode;
        this.currentVersion = currentVersion;
        this.retryAfterSeconds = retryAfterSeconds;
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Integer currentVersion() {
        return currentVersion;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public List<ProblemDetailResponse.Violation> violations() {
        return violations;
    }
}
