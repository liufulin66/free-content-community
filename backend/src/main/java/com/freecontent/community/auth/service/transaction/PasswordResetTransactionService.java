package com.freecontent.community.auth.service.transaction;

import com.freecontent.community.auth.config.PasswordResetProperties;
import com.freecontent.community.auth.dto.ResetPasswordRequest;
import com.freecontent.community.auth.mapper.EmailChallengeMapper;
import com.freecontent.community.auth.mapper.EmailDeliveryTaskMapper;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 密码、认证版本、验证码消费及邮件取消共享MySQL事务。 */
@Service
public class PasswordResetTransactionService {
    private final EmailChallengeVerificationService verification;
    private final EmailChallengeMapper challenges;
    private final EmailDeliveryTaskMapper tasks;
    private final UserAccountMapper users;
    private final PasswordResetProperties properties;
    private final Clock clock;

    public PasswordResetTransactionService(EmailChallengeVerificationService verification, EmailChallengeMapper challenges,
            EmailDeliveryTaskMapper tasks, UserAccountMapper users, PasswordResetProperties properties, Clock clock) {
        this.verification = verification;
        this.challenges = challenges;
        this.tasks = tasks;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Result reset(ResetPasswordRequest request, String normalizedEmail, String passwordHash) {
        if (!verification.verify(request.challengeId(), normalizedEmail, EmailChallengePurpose.PASSWORD_RESET,
                request.code(), properties.maxCodeFailures())) {
            // 允许错码计数提交；由事务外的业务入口返回400。
            return new Result(Status.INVALID_CHALLENGE, null, null);
        }
        UserAccount account = users.findByEmailForUpdate(normalizedEmail);
        Long newAuthVersion = null;
        if (account != null) {
            if (users.updatePasswordHash(account.getId(), passwordHash, account.getAuthVersion(), now()) != 1) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "密码重置暂未完成");
            }
            newAuthVersion = Math.addExact(account.getAuthVersion(), 1L);
        }
        LocalDateTime consumedAt = now();
        if (challenges.consumeForPurpose(request.challengeId(), EmailChallengePurpose.PASSWORD_RESET,
                properties.maxCodeFailures(), consumedAt) != 1) {
            throw new BusinessException(ErrorCode.CHALLENGE_INVALID, "验证码无效或已失效");
        }
        tasks.cancelAfterVerification(request.challengeId(), "CHALLENGE_CONSUMED", consumedAt);
        return account == null ? new Result(Status.NO_ACCOUNT, null, null)
                : new Result(Status.RESET, account.getId(), newAuthVersion);
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    public enum Status { RESET, NO_ACCOUNT, INVALID_CHALLENGE }
    public record Result(Status status, String userId, Long authVersion) { }
}
