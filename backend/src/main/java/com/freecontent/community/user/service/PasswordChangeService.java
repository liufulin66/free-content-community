package com.freecontent.community.user.service;

import com.freecontent.community.user.dto.ChangePasswordRequest;
import com.freecontent.community.auth.security.SessionPrincipal;

public interface PasswordChangeService {
    void change(ChangePasswordRequest request, SessionPrincipal currentSession, String sourceIp);
}
