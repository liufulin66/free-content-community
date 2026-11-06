package com.freecontent.community.auth.service;

import java.time.Clock;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.auth.session.StoredSession;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;

/** 会话确定身份，MySQL当前状态决定其是否仍有效及具有哪些权限。 */
@Service
public class SessionAuthenticationService {
    private final RedisSessionStore sessions;
    private final UserAccountMapper users;
    private final UserRoleMapper roles;
    private final Clock clock;

    public SessionAuthenticationService(RedisSessionStore sessions, UserAccountMapper users,
                                        UserRoleMapper roles, Clock clock) {
        this.sessions = sessions;
        this.users = users;
        this.roles = roles;
        this.clock = clock;
    }

    public SessionPrincipal authenticate(String token) {
        StoredSession session = sessions.find(token);
        if (session == null || !session.expiresAt().isAfter(clock.instant())) throw required();
        try {
            UserAccount account = users.selectById(session.userId());
            if (account == null || account.getAuthVersion() == null
                    || account.getAuthVersion() != session.authVersion()) throw required();
            if (account.getAccountStatus() == UserAccount.Status.DISABLED) {
                throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED, "账号已被禁用");
            }
            if (!Boolean.TRUE.equals(account.getEmailVerified())) {
                throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED, "请先完成邮箱验证");
            }
            return new SessionPrincipal(session.sessionId(),
                    CurrentUser.from(account, roles.findRoles(account.getId())), TokenDigest.sha256(token), session.authVersion());
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "账号认证服务暂不可用");
        }
    }

    private BusinessException required() { return new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED); }
}
