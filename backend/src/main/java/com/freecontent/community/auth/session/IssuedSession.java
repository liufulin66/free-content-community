package com.freecontent.community.auth.session;

public record IssuedSession(String accessToken, StoredSession session) {
    @Override
    public String toString() { return "IssuedSession[credentials=REDACTED]"; }
}
