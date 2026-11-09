package com.freecontent.community.auth.security;

import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuthorizationManagerTest {
    private final AdminAuthorizationManager manager = new AdminAuthorizationManager();

    @Test
    void adminCanCreateTag() {
        assertTrue(decide(auth(UserRole.USER, UserRole.ADMIN), "POST", "/api/v1/admin/tags").isGranted());
    }

    @Test
    void userCannotCreateTag() {
        assertFalse(decide(auth(UserRole.USER), "POST", "/api/v1/admin/tags").isGranted());
    }

    @Test
    void moderatorCannotCreateTag() {
        assertFalse(decide(auth(UserRole.USER, UserRole.MODERATOR), "POST", "/api/v1/admin/tags").isGranted());
    }

    @Test
    void unauthenticatedCannotCreateTag() {
        assertFalse(decide(null, "POST", "/api/v1/admin/tags").isGranted());
    }

    @Test
    void wrongMethodIsDenied() {
        assertFalse(decide(auth(UserRole.ADMIN), "PATCH", "/api/v1/admin/tags").isGranted());
    }

    @Test
    void unregisteredAdminPathIsDenied() {
        assertFalse(decide(auth(UserRole.ADMIN), "GET", "/api/v1/admin/reviews").isGranted());
    }

    private AuthorizationDecision decide(Authentication authentication, String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        return (AuthorizationDecision) manager.authorize(() -> authentication,
                new RequestAuthorizationContext(request));
    }

    private Authentication auth(UserRole... roles) {
        return UsernamePasswordAuthenticationToken.authenticated("user", null,
                Arrays.stream(roles).map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList());
    }
}
