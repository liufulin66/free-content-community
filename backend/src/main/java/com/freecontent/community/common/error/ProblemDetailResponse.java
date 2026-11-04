package com.freecontent.community.common.error;

import java.util.List;

/**
 * RFC 9457 Problem Details 的扩展响应体。
 * 字段名与契约一致：type/title/status/detail/instance/code/requestId/violations。
 */
public record ProblemDetailResponse(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        String requestId,
        List<Violation> violations
) {

    public record Violation(String field, String message) {
    }
}
