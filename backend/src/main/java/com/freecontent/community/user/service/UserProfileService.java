package com.freecontent.community.user.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.dto.UpdateProfileRequest;

public interface UserProfileService {
    CurrentUser update(UpdateProfileRequest request, SessionPrincipal currentSession, String sourceIp);
}
