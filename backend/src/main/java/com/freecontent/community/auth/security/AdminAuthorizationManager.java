package com.freecontent.community.auth.security;

import com.freecontent.community.user.model.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 后台路径的统一授权入口。
 *
 * <p>所有 /api/v1/admin/** 请求都由这里判断，不在 SecurityConfiguration 中逐路径散落角色规则。
 * 未登记的路径默认拒绝；新增后台接口时必须在本类登记允许角色。</p>
 */
public class AdminAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private static final PathPatternParser PATTERNS = new PathPatternParser();
    private static final List<Rule> RULES = List.of(
            new Rule(HttpMethod.POST, "/api/v1/admin/tags", Set.of(UserRole.ADMIN)),
            new Rule(HttpMethod.GET, "/api/v1/admin/tags", Set.of(UserRole.ADMIN)),
            new Rule(HttpMethod.PATCH, "/api/v1/admin/tags/{tagId}", Set.of(UserRole.ADMIN)),
            new Rule(HttpMethod.GET, "/api/v1/admin/tags/{tagId}", Set.of(UserRole.ADMIN)),
            new Rule(HttpMethod.GET, "/api/v1/admin/reviews", Set.of(UserRole.MODERATOR, UserRole.ADMIN)),
            new Rule(HttpMethod.GET, "/api/v1/admin/reviews/{reviewId}", Set.of(UserRole.MODERATOR, UserRole.ADMIN)),
            new Rule(HttpMethod.GET, "/api/v1/admin/reviews/{reviewId}/media/{assetId}/access", Set.of(UserRole.MODERATOR, UserRole.ADMIN)),
            new Rule(HttpMethod.POST, "/api/v1/admin/reviews/{reviewId}/decisions", Set.of(UserRole.MODERATOR, UserRole.ADMIN))
    );

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                          RequestAuthorizationContext context) {
        HttpServletRequest request = context.getRequest();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Set<UserRole> allowed = RULES.stream()
                .filter(rule -> rule.matches(request.getMethod(), path))
                .map(Rule::roles)
                .findFirst()
                .orElse(Set.of());
        if (allowed.isEmpty()) return new AuthorizationDecision(false);
        Authentication current = authentication.get();
        if (current == null || !current.isAuthenticated()) return new AuthorizationDecision(false);
        boolean granted = current.getAuthorities().stream()
                .anyMatch(authority -> allowed.stream()
                        .anyMatch(role -> authority.getAuthority().equals("ROLE_" + role.name())));
        return new AuthorizationDecision(granted);
    }

    private record Rule(HttpMethod method, PathPattern pattern, Set<UserRole> roles) {
        private Rule(HttpMethod method, String pattern, Set<UserRole> roles) {
            this(method, PATTERNS.parse(pattern), roles);
        }

        private boolean matches(String requestMethod, String path) {
            return method.matches(requestMethod) && pattern.matches(PathContainer.parsePath(path));
        }
    }
}
