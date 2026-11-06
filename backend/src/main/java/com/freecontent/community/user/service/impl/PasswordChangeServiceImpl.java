package com.freecontent.community.user.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.freecontent.community.auth.security.PasswordHasher;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.ChangePasswordRequest;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.ratelimit.PasswordChangeRateLimiter;
import com.freecontent.community.user.service.PasswordChangeService;
import com.freecontent.community.user.service.transaction.PasswordChangeTransactionService;

@Service
public class PasswordChangeServiceImpl implements PasswordChangeService {
    private static final Logger log = LoggerFactory.getLogger(PasswordChangeServiceImpl.class);
    private final PasswordChangeRateLimiter limiter;
    private final PasswordHasher passwords;
    private final UserAccountMapper users;
    private final PasswordChangeTransactionService transactions;
    private final RedisSessionStore sessions;

    public PasswordChangeServiceImpl(PasswordChangeRateLimiter limiter, PasswordHasher passwords, UserAccountMapper users,
                                    PasswordChangeTransactionService transactions, RedisSessionStore sessions) {
        this.limiter = limiter;
        this.passwords = passwords;
        this.users = users;
        this.transactions = transactions;
        this.sessions = sessions;
    }

    @Override
    public void change(ChangePasswordRequest request, SessionPrincipal currentSession, String sourceIp) {
        
        if (currentSession == null || currentSession.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        String userId = currentSession.user().id();
        
        limiter.acquire(userId, sourceIp);
        long newAuthVersion;
        try {
            UserAccount account = users.selectById(userId);
            if (account == null || account.getAuthVersion() == null || account.getAuthVersion() != currentSession.authVersion()) {
                throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
            }
            if (account.getAccountStatus() == UserAccount.Status.DISABLED) {
                throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED, "账号已被禁用");
            }
            if (!passwords.matches(request.currentPassword(), account.getPasswordHash())) {
                throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "当前密码不正确");
            }
            String newHash = passwords.encode(request.newPassword());
            newAuthVersion = transactions.change(userId, account.getAuthVersion(), account.getPasswordHash(), newHash);
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "修改密码数据服务暂不可用");
        }
        try {
            sessions.revokeBeforeVersion(userId, newAuthVersion);
        } catch (BusinessException ex) {
            if (ex.errorCode() != ErrorCode.DEPENDENCY_UNAVAILABLE) throw ex;
            log.warn("Password change committed; Redis session cleanup pending, userId={}", userId);
        }
    }
}
