package com.freecontent.community.auth.service.impl;

import java.util.Locale;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.freecontent.community.auth.dto.LoginRequest;
import com.freecontent.community.auth.dto.LoginResult;
import com.freecontent.community.auth.ratelimit.LoginRateLimiter;
import com.freecontent.community.auth.security.PasswordHasher;
import com.freecontent.community.auth.service.LoginService;
import com.freecontent.community.auth.session.IssuedSession;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;

/** 密码计算不占数据库事务；签发前复核安全状态，后续请求继续核对认证版本。 */
@Service
public class LoginServiceImpl implements LoginService {
    private final LoginRateLimiter limiter;
    private final PasswordHasher passwords;
    private final UserAccountMapper users;
    private final UserRoleMapper roles;
    private final RedisSessionStore sessions;

    public LoginServiceImpl(LoginRateLimiter limiter, PasswordHasher passwords, UserAccountMapper users,
                        UserRoleMapper roles, RedisSessionStore sessions) {
        this.limiter = limiter;
        this.passwords = passwords;
        this.users = users;
        this.roles = roles;
        this.sessions = sessions;
    }

    @Override
    public LoginResult login(LoginRequest request, String sourceIp) {
        String email = request.email().toLowerCase(Locale.ROOT);
        limiter.acquire(email, sourceIp);
        try {
            // 查找用户账户
            UserAccount original = users.findByEmail(email);
            if (original == null) {
                passwords.verifyMissingAccount(request.password());
                throw failed();
            }
            // 验证密码
            if (!passwords.matches(request.password(), original.getPasswordHash())) throw failed();
            UserAccount current = users.selectById(original.getId());
            // 复核账户状态和认证版本，防止在登录过程中被禁用或修改密码
            if (current == null || !Objects.equals(current.getAuthVersion(), original.getAuthVersion())
                    || !Objects.equals(current.getPasswordHash(), original.getPasswordHash())) throw failed();
                // 复核账户状态和邮箱验证状态
            if (current.getAccountStatus() == UserAccount.Status.DISABLED) {
                throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED, "账号已被禁用");
            }
            // 复核邮箱验证状态
            if (!Boolean.TRUE.equals(current.getEmailVerified())) {
                throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED, "请先完成邮箱验证");
            }
            // 构建当前用户信息
            CurrentUser user = CurrentUser.from(current, roles.findRoles(current.getId()));
            // 签发会话
            IssuedSession issued = sessions.create(current.getId(), current.getAuthVersion(), request.deviceLabel());
            return new LoginResult(issued.accessToken(), "Bearer", issued.session().expiresAt(),
                    issued.session().sessionId(), user);
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "登录数据服务暂不可用");
        }
    }

    private BusinessException failed() {
        return new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "邮箱或密码不正确");
    }
}
