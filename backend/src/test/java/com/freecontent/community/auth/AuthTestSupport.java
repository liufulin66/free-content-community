package com.freecontent.community.auth;

import com.freecontent.community.auth.config.EmailChallengeProperties;

import java.security.SecureRandom;
import java.util.Base64;

public final class AuthTestSupport {

    private AuthTestSupport() {
    }

    public static EmailChallengeProperties properties() {
        return new EmailChallengeProperties(600, 60, 5, 100,
                3, 1000, 60, 5, true, "no-reply@example.test",
                "v1", randomKey(), "v1", randomKey(), "test:auth:");
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
