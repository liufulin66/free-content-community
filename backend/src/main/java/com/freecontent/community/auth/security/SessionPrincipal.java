package com.freecontent.community.auth.security;

import com.freecontent.community.user.dto.CurrentUser;

/** 仅在服务端认证上下文中使用；不保存原始Token。 */
public record SessionPrincipal(String sessionId, CurrentUser user, String tokenDigest, long authVersion) { }
