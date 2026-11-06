package com.freecontent.community.auth.service.impl;

import com.freecontent.community.auth.config.PasswordResetProperties;
import com.freecontent.community.auth.dto.ResetPasswordRequest;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.auth.ratelimit.PasswordResetRateLimiter;
import com.freecontent.community.auth.security.PasswordHasher;
import com.freecontent.community.auth.service.PasswordResetService;
import com.freecontent.community.auth.service.transaction.EmailChallengeVerificationService;
import com.freecontent.community.auth.service.transaction.PasswordResetTransactionService;
import com.freecontent.community.auth.session.RedisSessionStore;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import java.util.Locale;

@Service
public class PasswordResetServiceImpl implements PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetServiceImpl.class);
    private final PasswordResetRateLimiter limiter;
    private final EmailChallengeVerificationService verification;
    private final PasswordHasher passwords;
    private final PasswordResetTransactionService transactions;
    private final RedisSessionStore sessions;
    private final PasswordResetProperties properties;

    public PasswordResetServiceImpl(PasswordResetRateLimiter limiter, EmailChallengeVerificationService verification,
            PasswordHasher passwords, PasswordResetTransactionService transactions, RedisSessionStore sessions,
            PasswordResetProperties properties) {
        this.limiter = limiter;
        this.verification = verification;
        this.passwords = passwords;
        this.transactions = transactions;
        this.sessions = sessions;
        this.properties = properties;
    }

    @Override
    public void reset(ResetPasswordRequest request, String sourceIp) {
        String email = request.email().toLowerCase(Locale.ROOT);
        limiter.acquire(email, sourceIp);
        PasswordResetTransactionService.Result result;
        try {
            if (!verification.verify(request.challengeId(), email, EmailChallengePurpose.PASSWORD_RESET,
                    request.code(), properties.maxCodeFailures())) throw invalidChallenge();
            // 正确验证码才触发高成本哈希；有效验证码用于不存在的账号时也消费并返回204。
            String hash = passwords.encode(request.newPassword());
            result = transactions.reset(request, email, hash);
            if (result.status() == PasswordResetTransactionService.Status.INVALID_CHALLENGE) throw invalidChallenge();
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "密码重置数据服务暂不可用");
        }
        if (result.status() == PasswordResetTransactionService.Status.RESET) {
            try {
                // 写事务已提交，旧版本已无法认证；仅清理旧版本，保留之后新登录的会话。
                sessions.revokeBeforeVersion(result.userId(), result.authVersion());
            } catch (BusinessException ex) {
                if (ex.errorCode() != ErrorCode.DEPENDENCY_UNAVAILABLE) throw ex;
                // 不输出邮箱、密码、验证码或Token；后续登录和TTL会清理残留记录。
                log.warn("Password reset committed; Redis session cleanup pending, userId={}", result.userId());
            }
        }
    }

    private BusinessException invalidChallenge() {
        return new BusinessException(ErrorCode.CHALLENGE_INVALID, "验证码无效或已失效");
    }
}
