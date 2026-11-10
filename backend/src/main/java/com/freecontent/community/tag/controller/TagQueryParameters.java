package com.freecontent.community.tag.controller;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Set;

/** 后台标签接口在调用业务服务及计数前拒绝未知或重复查询参数。 */
final class TagQueryParameters {
    private TagQueryParameters() { }

    static void validate(HttpServletRequest request, Set<String> allowed) {
        for (var entry : request.getParameterMap().entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue().length != 1) {
                throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "查询参数未定义或重复", null, null,
                        List.of(ProblemDetailResponse.Violation.query(entry.getKey(), "查询参数未定义或重复")));
            }
        }
    }
}
