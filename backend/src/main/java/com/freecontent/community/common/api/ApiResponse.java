package com.freecontent.community.common.api;

import java.time.Instant;

/**
 * 契约规定的统一成功响应体：data + requestId + serverTime。
 * 204 响应不使用该包装。
 */
public record ApiResponse<T>(T data, String requestId, Instant serverTime) {

    public static <T> ApiResponse<T> of(T data, String requestId) {
        return new ApiResponse<>(data, requestId, Instant.now());
    }
}
