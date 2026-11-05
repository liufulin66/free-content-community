package com.freecontent.community.auth.service;

import java.util.Locale;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.freecontent.community.auth.dto.RegisterRequest;
import com.freecontent.community.auth.ratelimit.RegistrationRateLimiter;
import com.freecontent.community.auth.security.PasswordHasher;
import com.freecontent.community.auth.service.transaction.RegistrationTransactionService;
import com.freecontent.community.auth.service.transaction.RegistrationVerificationService;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.CurrentUser;

/** 注册业务入口：组织限流、验证码预检查、密码哈希和最终注册事务。 */
@Service
public class RegistrationService {
    private final RegistrationRateLimiter limiter;
    private final RegistrationVerificationService verification;
    private final PasswordHasher passwords;
    private final RegistrationTransactionService transactions;

    public RegistrationService(RegistrationRateLimiter limiter, RegistrationVerificationService verification,
                               PasswordHasher passwords, RegistrationTransactionService transactions) {
        this.limiter = limiter;
        this.verification = verification;
        this.passwords = passwords;
        this.transactions = transactions;
    }

    // 注册入口方法，处理注册请求
    public CurrentUser register(RegisterRequest request, String sourceIp) {
        // 将邮箱地址转换为小写，以确保一致性和避免重复
        String email = request.email().toLowerCase(Locale.ROOT);
        // 进行速率限制检查，确保同一邮箱或IP地址在一定时间内不会频繁请求注册
        limiter.acquire(email, sourceIp);
        try {
            // 验证验证码的有效性，如果无效则抛出异常
            if (!verification.verify(request, email)) {
                throw invalidChallenge();
            }
            // 无效验证码不会触发高成本哈希；有效验证码在哈希后还需在写事务内复核。
            String passwordHash = passwords.encode(request.password());
            // 调用事务服务进行注册操作，根据返回的状态决定下一步操作
            RegistrationTransactionService.Result result = transactions.register(request, email, passwordHash);
            return switch (result.status()) {
                case REGISTERED -> result.user();
                case INVALID_CHALLENGE -> throw invalidChallenge();
                case EMAIL_UNAVAILABLE -> throw new BusinessException(
                        ErrorCode.REGISTRATION_NOT_ALLOWED, "当前无法为该邮箱创建账号");
            };
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.REGISTRATION_NOT_ALLOWED, "当前无法为该邮箱创建账号");
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "注册数据服务暂不可用");
        }
    }

    // 创建一个通用的异常方法，用于处理无效验证码的情况
    private BusinessException invalidChallenge() {
        return new BusinessException(ErrorCode.CHALLENGE_INVALID, "验证码无效或已失效");
    }
}
