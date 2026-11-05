package com.freecontent.community.auth.dto;

import com.freecontent.community.user.dto.CurrentUser;
import java.time.Instant;

public record LoginResult(String accessToken, String tokenType, Instant expiresAt,
                          String sessionId, CurrentUser user) {
    @Override
    public String toString() {
        return "LoginResult[credentials=REDACTED]";
    }
}
