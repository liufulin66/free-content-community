package com.freecontent.community.auth.security;

import com.freecontent.community.user.dto.CurrentUser;

public record SessionPrincipal(String sessionId, CurrentUser user) { }
