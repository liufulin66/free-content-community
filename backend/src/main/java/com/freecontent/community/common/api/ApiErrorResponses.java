package com.freecontent.community.common.api;

import com.freecontent.community.common.error.ErrorCode;
import java.lang.annotation.*;

/**
 * 仅声明OpenAPI错误文档，不参与运行时异常处理。
 * 受保护接口使用默认includeCommon=true，自动包含认证、账号、校验、限流等公共错误；
 * 公开接口必须设置includeCommon=false并显式声明完整错误集合，OpenAPI生成时会拒绝误用。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ApiErrorResponses {
    ErrorCode[] value() default {};
    /** 公开接口等特殊场景可关闭默认集合，显式声明完整错误码。 */
    boolean includeCommon() default true;
}
