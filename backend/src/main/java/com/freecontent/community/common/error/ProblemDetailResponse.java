package com.freecontent.community.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * RFC 9457 Problem Details 的扩展响应体。
 * 字段名与契约一致：type/title/status/detail/instance/code/requestId/violations/currentVersion。
 *
 * <p>currentVersion 只在版本冲突场景出现；NON_NULL 保证它不会以 null 形式写进响应。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetailResponse(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        String requestId,
        List<Violation> violations,
        Integer currentVersion
) {

    /**
     * 契约中的校验错误项是 location/path/reason，不是 field/message。
     */
    public record Violation(String location, String path, String reason) {

        public static Violation body(String path, String reason) {
            return new Violation("body", path, reason);
        }

        public static Violation query(String path, String reason) {
            return new Violation("query", path, reason);
        }
    }
}
