package com.freecontent.community.auth.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.freecontent.community.auth.config.EmailChallengeProperties;
import com.freecontent.community.auth.dto.ChallengeReceipt;
import com.freecontent.community.auth.dto.CreateEmailChallengeRequest;
import com.freecontent.community.auth.entity.EmailChallenge;
import com.freecontent.community.auth.entity.EmailDeliveryTask;
import com.freecontent.community.auth.security.EmailChallengeCrypto;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;

@Service
public class EmailChallengeService {

    private final EmailChallengeRateLimiter limiter;
    private final EmailChallengeCrypto crypto;
    private final EmailChallengeWriter writer;
    private final EmailChallengeProperties properties;

    public EmailChallengeService(EmailChallengeRateLimiter limiter, EmailChallengeCrypto crypto,
                                 EmailChallengeWriter writer, EmailChallengeProperties properties) {
        this.limiter = limiter;
        this.crypto = crypto;
        this.writer = writer;
        this.properties = properties;
    }

    public ChallengeReceipt create(CreateEmailChallengeRequest request, String sourceIp) {
        // 将邮箱地址转换为小写，以确保一致性和避免重复
        String email = request.email().toLowerCase(Locale.ROOT);
        // 进行速率限制检查，确保同一邮箱或IP地址在一定时间内不会频繁请求验证码
        Instant issuedAt = limiter.acquire(email, request.purpose(), sourceIp);
        // 计算验证码的过期时间和重新发送时间
        Instant expiresAt = issuedAt.plusSeconds(properties.ttlSeconds());
        // 计算重新发送验证码的时间，确保用户在一定时间内不能频繁请求验证码
        Instant resendAfter = issuedAt.plusSeconds(properties.resendIntervalSeconds());
        // 生成唯一的验证码ID和任务ID，并生成验证码
        String challengeId = UUID.randomUUID().toString().replace("-", "");
        // 生成唯一的任务ID，用于标识验证码发送任务
        String taskId = UUID.randomUUID().toString().replace("-", "");
        // 生成验证码
        String code = crypto.generateCode();
        // 使用加密算法对验证码进行加密，确保验证码在传输和存储过程中不会被泄露
        EmailChallengeCrypto.EncryptedCode encrypted = crypto.encrypt(taskId, challengeId, code);
        // 将当前时间转换为UTC时间，以确保在不同地区和时区之间的一致性
        LocalDateTime createdAt = utc(issuedAt);

        // 创建EmailChallenge对象，保存验证码相关信息，包括邮箱、用途、验证码摘要、过期时间等
        EmailChallenge challenge = new EmailChallenge();
        challenge.setId(challengeId);
        challenge.setEmailNormalized(email);
        challenge.setDeliveryEmail(request.email());
        challenge.setPurpose(request.purpose());
        challenge.setCodeMac(crypto.digest(challengeId, email, request.purpose(), code));
        challenge.setMacKeyId(crypto.macKeyId());
        challenge.setStatus(EmailChallenge.Status.ACTIVE);
        challenge.setExpiresAt(utc(expiresAt));
        challenge.setResendAfter(utc(resendAfter));
        challenge.setCreatedAt(createdAt);
        challenge.setUpdatedAt(createdAt);

        // 创建EmailDeliveryTask对象，保存验证码发送任务相关信息，包括任务ID、验证码ID、加密后的验证码、状态等
        EmailDeliveryTask task = new EmailDeliveryTask();
        task.setId(taskId);
        task.setChallengeId(challengeId);
        task.setCodeCiphertext(encrypted.ciphertext());
        task.setEncryptionIv(encrypted.iv());
        task.setEncryptionKeyId(encrypted.keyId());
        task.setStatus(EmailDeliveryTask.Status.PENDING);
        task.setNextAttemptAt(createdAt);
        task.setCreatedAt(createdAt);
        task.setUpdatedAt(createdAt);
        try {
            writer.save(challenge, task);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "验证码申请发生并发竞争，请稍后重试",
                    null, properties.resendIntervalSeconds(), List.of());
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "验证码保存服务暂不可用");
        }
        return new ChallengeReceipt(challengeId, expiresAt, resendAfter);
    }

    // 将Instant转换为UTC的LocalDateTime
    private LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
