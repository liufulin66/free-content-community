package com.freecontent.community.user.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.user.dto.PublicUserView;

public interface PublicUserService {
    PublicUserView getPublicUser(String userId, SessionPrincipal viewer, String sourceIp);
}
