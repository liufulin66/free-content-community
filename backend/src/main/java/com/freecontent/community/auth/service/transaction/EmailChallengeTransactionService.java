package com.freecontent.community.auth.service.transaction;

import com.freecontent.community.auth.entity.EmailChallenge;
import com.freecontent.community.auth.entity.EmailDeliveryTask;
import com.freecontent.community.auth.mapper.EmailChallengeMapper;
import com.freecontent.community.auth.mapper.EmailDeliveryTaskMapper;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 独立Bean承接事务，避免同类内部调用绕过事务代理。
 * SMTP、Redis和加密计算均在该数据库事务之外执行。
 */
@Service
public class EmailChallengeTransactionService {

    private final EmailChallengeMapper challenges;
    private final EmailDeliveryTaskMapper tasks;
    private final Clock clock;

    public EmailChallengeTransactionService(EmailChallengeMapper challenges, EmailDeliveryTaskMapper tasks, Clock clock) {
        this.challenges = challenges;
        this.tasks = tasks;
        this.clock = clock;
    }

    @Transactional
    public void save(EmailChallenge challenge, EmailDeliveryTask task) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!challenge.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "验证码申请处理超时，请稍后重试");
        }
        EmailChallenge existing = challenges.findActiveForUpdate(
                challenge.getEmailNormalized(), challenge.getPurpose());
        if (existing != null) {
            // 使用本次申请的受理时刻比较，较早但迟到的请求不能覆盖较新申请。
            if (challenge.getCreatedAt().isBefore(existing.getResendAfter())) {
                long millis = Duration.between(now, existing.getResendAfter()).toMillis();
                throw new BusinessException(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试",
                        null, Math.max(1, (millis + 999) / 1000), List.of());
            }
            if (challenges.invalidate(existing.getId(), now) != 1) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "验证码申请状态已变化");
            }
            tasks.cancelForChallenge(existing.getId(), now);
        }
        if (challenges.insert(challenge) != 1 || tasks.insert(task) != 1) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "验证码申请保存失败");
        }
    }
}
