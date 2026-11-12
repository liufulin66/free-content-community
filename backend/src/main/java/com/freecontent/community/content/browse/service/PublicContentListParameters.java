package com.freecontent.community.content.browse.service;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.common.error.ProblemDetailResponse;
import java.math.BigInteger;
import java.util.List;

/** 全站和作者列表共用的分页契约；参数校验发生在游标验证及配额计数之前。 */
public final class PublicContentListParameters {
    private PublicContentListParameters() { }

    public static int pageSize(String value) {
        if (value == null) return 20;
        if (!value.matches("-?[0-9]+")) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "limit必须是整数", null, null,
                    List.of(ProblemDetailResponse.Violation.query("limit", "必须是整数")));
        }
        BigInteger number = new BigInteger(value);
        if (number.signum() <= 0 || number.compareTo(BigInteger.valueOf(50)) > 0) throw invalidQuery("limit", "必须在1到50之间");
        return number.intValueExact();
    }

    public static void requireCursorLength(String cursor) {
        if (cursor != null && cursor.codePointCount(0, cursor.length()) > 4096) {
            throw invalidQuery("cursor", "游标不能超过4096个字符");
        }
    }

    public static BusinessException invalidQuery(String field, String reason) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "列表查询参数不合法", null, null,
                List.of(ProblemDetailResponse.Violation.query(field, reason)));
    }
}
