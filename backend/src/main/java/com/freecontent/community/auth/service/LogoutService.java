package com.freecontent.community.auth.service;

import com.freecontent.community.auth.security.SessionPrincipal;

/** 撤销当前已认证会话的业务接口。 */
public interface LogoutService {
    void logoutCurrentSession(SessionPrincipal currentSession);
}
