package com.freecontent.community.auth.service.transaction;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.freecontent.community.auth.entity.EmailChallenge;
import com.freecontent.community.auth.mapper.EmailChallengeMapper;
import com.freecontent.community.auth.mapper.EmailDeliveryTaskMapper;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.auth.security.EmailChallengeCrypto;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;

/** 按后端指定用途校验验证码，供注册和密码重置复用。 */
@Service
public class EmailChallengeVerificationService {
    private final EmailChallengeMapper challenges;
    private final EmailDeliveryTaskMapper tasks;
    private final EmailChallengeCrypto crypto;
    private final Clock clock;

    public EmailChallengeVerificationService(EmailChallengeMapper challenges, EmailDeliveryTaskMapper tasks,
                                             EmailChallengeCrypto crypto, Clock clock) {
        this.challenges = challenges;
        this.tasks = tasks;
        this.crypto = crypto;
        this.clock = clock;
    }

    /**
     * 错码以false返回，让失败计数提交后，再由事务外的Service抛出400。
     * 最终写事务再次调用时加入同一事务，重新检查过期、重发和消费竞争。
     */
    @Transactional
    public boolean verify(String challengeId, String normalizedEmail, EmailChallengePurpose expectedPurpose,
                          String code, int maxFailures) {
        EmailChallenge challenge = challenges.findByIdForUpdate(challengeId);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        // 用途来自后端业务入口，不允许客户端选择校验用途。
        if (challenge == null || challenge.getStatus() != EmailChallenge.Status.ACTIVE
                || challenge.getPurpose() != expectedPurpose
                || !normalizedEmail.equals(challenge.getEmailNormalized())
                || !challenge.getExpiresAt().isAfter(now)) {
            return false;
        }
        // 错码次数达到上限时锁定验证码，取消后续投递任务，并返回false。
        int max = maxFailures;
        if (challenge.getFailedAttempts() >= max) {
            challenges.lockAtFailureLimit(challenge.getId(), max, now);
            tasks.cancelAfterVerification(challenge.getId(), "CHALLENGE_LOCKED", now);
            return false;
        }
        // 验证码MAC校验失败时，记录失败次数，达到上限时锁定验证码并取消后续投递任务。
        if (!crypto.matches(challenge.getMacKeyId(), challenge.getCodeMac(), challenge.getId(),
                normalizedEmail, expectedPurpose, code)) {
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
