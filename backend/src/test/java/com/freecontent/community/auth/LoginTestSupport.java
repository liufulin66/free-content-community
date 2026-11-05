package com.freecontent.community.auth;

import com.freecontent.community.auth.config.LoginProperties;
import com.freecontent.community.user.entity.UserAccount;
import java.time.LocalDateTime;

public final class LoginTestSupport {
    private LoginTestSupport() { }

    public static LoginProperties properties(String prefix) {
        return new LoginProperties(86400, 5, 30, 10, 900, prefix);
    }

    public static UserAccount account() {
        UserAccount user = new UserAccount();
        user.setId("user1");
        user.setEmail("Creator@Example.test");
        user.setEmailNormalized("creator@example.test");
        user.setPasswordHash("stored-hash");
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(LocalDateTime.of(2026, 11, 5, 0, 0));
        user.setDisplayName("创作者");
        user.setBio("");
        user.setAccountStatus(UserAccount.Status.ACTIVE);
        user.setVersion(1);
        user.setCreatedAt(user.getEmailVerifiedAt());
        user.setUpdatedAt(user.getCreatedAt());
        return user;
    }
}
