package com.freecontent.community.auth.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.service.LogoutService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class LogoutServiceImpl implements LogoutService {
    private final RedisSessionStore sessions;

    public LogoutServiceImpl(RedisSessionStore sessions) {
        this.sessions = sessions;
    }

    @Override
    public void logoutCurrentSession(SessionPrincipal currentSession) {
        if (currentSession == null || currentSession.user() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        sessions.revoke(currentSession.user().id(), currentSession.sessionId(), currentSession.tokenDigest());
    }
}
