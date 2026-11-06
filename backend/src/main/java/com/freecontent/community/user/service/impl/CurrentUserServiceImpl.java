package com.freecontent.community.user.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.service.CurrentUserService;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserServiceImpl implements CurrentUserService {
    @Override
    public CurrentUser getCurrentUser(SessionPrincipal currentSession) {
        if (currentSession == null || currentSession.user() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        // 认证阶段已从MySQL读取本次请求的账号和角色，直接复用，避免重复查询。
        return currentSession.user();
    }
}
