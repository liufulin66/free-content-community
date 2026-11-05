package com.freecontent.community.auth.service.transaction;

import com.freecontent.community.auth.config.RegistrationProperties;
import com.freecontent.community.auth.dto.RegisterRequest;
import com.freecontent.community.auth.entity.EmailChallenge;
import com.freecontent.community.auth.mapper.EmailChallengeMapper;
import com.freecontent.community.auth.mapper.EmailDeliveryTaskMapper;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.auth.security.EmailChallengeCrypto;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 注册验证码校验与错码状态管理，负责提交失败次数和锁定状态。 */
@Service
public class RegistrationVerificationService {
    private final EmailChallengeMapper challenges;
    private final EmailDeliveryTaskMapper tasks;
    private final EmailChallengeCrypto crypto;
    private final RegistrationProperties properties;
    private final Clock clock;

    public RegistrationVerificationService(EmailChallengeMapper challenges, EmailDeliveryTaskMapper tasks,
                                         EmailChallengeCrypto crypto, RegistrationProperties properties,
                                         Clock clock) {
        this.challenges = challenges;
        this.tasks = tasks;
        this.crypto = crypto;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 错码以false返回，让失败计数提交后，再由事务外的Service抛出400。
     * 注册写事务再次调用时加入同一事务，重新检查过期、重发和消费竞争。
     */
    @Transactional
    public boolean verify(RegisterRequest request, String normalizedEmail) {
        EmailChallenge challenge = challenges.findByIdForUpdate(request.challengeId());
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (challenge == null || challenge.getStatus() != EmailChallenge.Status.ACTIVE
                || challenge.getPurpose() != EmailChallengePurpose.REGISTER
                || !normalizedEmail.equals(challenge.getEmailNormalized())
                || !challenge.getExpiresAt().isAfter(now)) {
            return false;
        }
        int max = properties.maxCodeFailures();
        if (challenge.getFailedAttempts() >= max) {
            challenges.lockAtFailureLimit(challenge.getId(), max, now);
            tasks.cancelAfterVerification(challenge.getId(), "CHALLENGE_LOCKED", now);
            return false;
        }
        if (!crypto.matches(challenge.getMacKeyId(), challenge.getCodeMac(), challenge.getId(),
                normalizedEmail, EmailChallengePurpose.REGISTER, request.code())) {
            if (challenges.recordFailedAttempt(challenge.getId(), max, now) != 1) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "验证码状态已变化");
            }
            if (challenge.getFailedAttempts() + 1 >= max) {
                tasks.cancelAfterVerification(challenge.getId(), "CHALLENGE_LOCKED", now);
            }
            return false;
        }
        return true;
    }
}
