package com.freecontent.community.common.error;

import org.springframework.http.HttpStatus;

import java.util.Locale;

/**
 * P0 契约中的稳定业务错误码。
 * code 供客户端分支处理；title 只用于人类阅读；type 是 RFC 9457 的问题类型 URI。
 */
public enum ErrorCode {

    // 400
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "请求格式错误"),
    CHALLENGE_INVALID(HttpStatus.BAD_REQUEST, "验证码无效"),
    CURSOR_INVALID(HttpStatus.BAD_REQUEST, "游标无效"),

    // 401
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "需要登录"),
    AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "认证失败"),

    // 403
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, "权限不足"),
    ACCOUNT_RESTRICTED(HttpStatus.FORBIDDEN, "账号受限"),
    EMAIL_VERIFICATION_REQUIRED(HttpStatus.FORBIDDEN, "需要先验证邮箱"),
    REGISTRATION_NOT_ALLOWED(HttpStatus.FORBIDDEN, "当前不允许注册"),

    // 404 / 405
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),

    // 409
    CONTENT_VERSION_CONFLICT(HttpStatus.CONFLICT, "内容版本冲突"),
    RESOURCE_VERSION_CONFLICT(HttpStatus.CONFLICT, "资源版本冲突"),
    REVIEW_VERSION_CONFLICT(HttpStatus.CONFLICT, "审核版本冲突"),
    CONTENT_NOT_EDITABLE(HttpStatus.CONFLICT, "内容当前不可编辑"),
    DRAFT_NOT_EDITABLE(HttpStatus.CONFLICT, "草稿当前不可编辑"),
    DRAFT_ALREADY_EXISTS(HttpStatus.CONFLICT, "草稿已存在"),
    REVIEW_ALREADY_PENDING(HttpStatus.CONFLICT, "已有待审核申请"),
    REVIEW_NOT_PENDING(HttpStatus.CONFLICT, "审核不在待处理状态"),
    REMEDIATION_REQUIRED(HttpStatus.CONFLICT, "需要整改后重新提交审核"),
    MEDIA_NOT_READY(HttpStatus.CONFLICT, "素材尚未就绪"),
    UPLOAD_STATE_CONFLICT(HttpStatus.CONFLICT, "上传状态冲突"),
    TAG_NAME_CONFLICT(HttpStatus.CONFLICT, "标签名称冲突"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "幂等键被重复使用"),
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "相同请求正在处理中"),

    // 410
    CURSOR_EXPIRED(HttpStatus.GONE, "游标已过期"),

    // 413 / 415 / 422 / 429 / 500 / 503
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "请求内容过大"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的媒体类型"),
    VALIDATION_ERROR(HttpStatus.UNPROCESSABLE_CONTENT, "请求参数校验失败"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误"),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "依赖服务暂不可用");

    private final HttpStatus httpStatus;
    private final String title;

    ErrorCode(HttpStatus httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    public int statusCode() {
        return httpStatus.value();
    }

    public String title() {
        return title;
    }

    /**
     * 与契约示例一致：urn:free-content:problem:content-version-conflict。
     */
    public String type() {
        return "urn:free-content:problem:" + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
