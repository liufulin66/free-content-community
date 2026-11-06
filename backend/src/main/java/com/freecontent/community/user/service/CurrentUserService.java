package com.freecontent.community.user.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.user.dto.CurrentUser;

public interface CurrentUserService {
    CurrentUser getCurrentUser(SessionPrincipal currentSession);
}
