package com.freecontent.community.auth.security;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.entity.UserAccount;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 由Security链持有，不注册为Servlet Filter，避免重复执行。 */
public class BearerSessionFilter extends OncePerRequestFilter {
    public static final Set<String> PUBLIC_AUTH_PATHS = Set.of("/api/v1/auth/email-challenges",
            "/api/v1/auth/registrations", "/api/v1/auth/sessions", "/api/v1/auth/password-resets");
    public static final Set<String> PUBLIC_READ_PATHS = Set.of("/api/v1/tags");
    private final SessionAuthenticationService authentication;
    private final HandlerExceptionResolver errors;

    public BearerSessionFilter(SessionAuthenticationService authentication, HandlerExceptionResolver errors) {
        this.authentication = authentication;
        this.errors = errors;
    }
    
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return ("POST".equals(request.getMethod()) && PUBLIC_AUTH_PATHS.contains(path))
                || ("GET".equals(request.getMethod()) && PUBLIC_READ_PATHS.contains(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        try {
            if (header != null) {
                var headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
                headers.nextElement();
                if (headers.hasMoreElements() || !header.matches("(?i:Bearer) +[A-Za-z0-9_-]{43}")) {
                    throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
                }
                SessionPrincipal principal = authentication.authenticate(header.substring(header.lastIndexOf(' ') + 1));
                if (principal.user().accountStatus() == UserAccount.Status.WRITE_BLOCKED
                        && isBusinessWrite(request)) {
                    throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED, "账号暂不能进行业务写入");
                }
                var authorities = principal.user().roles().stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
                SecurityContextHolder.setContext(context);
            }
        } catch (BusinessException ex) {
            SecurityContextHolder.clearContext();
            errors.resolveException(request, response, null, contractAuthenticationFailure(request, ex));
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private BusinessException contractAuthenticationFailure(HttpServletRequest request, BusinessException failure) {
        if (failure.errorCode() != ErrorCode.EMAIL_VERIFICATION_REQUIRED) return failure;
        // 当前已实现的受保护接口中，仅创建上传的契约声明邮箱未验证这一专用错误码。
        // 仍拒绝其余请求，只将对外错误码收敛为契约允许的PERMISSION_DENIED。
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("POST".equals(request.getMethod()) && "/api/v1/media/uploads".equals(path)) return failure;
        return new BusinessException(ErrorCode.PERMISSION_DENIED, failure.getMessage());
    }

    // 业务写入请求包括除GET/HEAD/OPTIONS外的所有请求，除了PUT /api/v1/me/password和DELETE /api/v1/auth/sessions/current及DELETE /api/v1/me/sessions/{id}。
    private boolean isBusinessWrite(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (Set.of("GET", "HEAD", "OPTIONS").contains(method)) return false;
        if ("PUT".equals(method) && "/api/v1/me/password".equals(path)) return false;
        return !("DELETE".equals(method) && ("/api/v1/auth/sessions/current".equals(path)
                || path.matches("/api/v1/me/sessions/[A-Za-z0-9_-]{1,64}")));
    }
}
