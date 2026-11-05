package com.freecontent.community.auth.service;

import com.freecontent.community.auth.config.EmailChallengeProperties;
import com.freecontent.community.auth.entity.EmailChallenge;
import com.freecontent.community.auth.entity.EmailDeliveryTask;
import com.freecontent.community.auth.mapper.EmailChallengeMapper;
import com.freecontent.community.auth.mapper.EmailDeliveryTaskMapper;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.auth.security.EmailChallengeCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 数据库原子认领和租约令牌保护回写；SMTP不占用长事务。
 * SMTP已接收但回写前崩溃时可能重复投递，同一任务始终使用原验证码。
 */
@Component
public class EmailDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryWorker.class);
    private final EmailDeliveryTaskMapper tasks;
    private final EmailChallengeMapper challenges;
    private final EmailChallengeCrypto crypto;
    private final JavaMailSender sender;
    private final EmailChallengeProperties properties;
    private final Clock clock;

    public EmailDeliveryWorker(EmailDeliveryTaskMapper tasks, EmailChallengeMapper challenges,
                               EmailChallengeCrypto crypto, JavaMailSender sender,
                               EmailChallengeProperties properties, Clock clock) {
        this.tasks = tasks;
        this.challenges = challenges;
        this.crypto = crypto;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.auth.email-challenge.poll-delay-millis:1000}")
    public void poll() {
        if (!properties.deliveryEnabled()) {
            return;
        }
        try {
            for (String id : tasks.findDue(now(), 10)) {
                try {
                    deliver(id);
                } catch (DataAccessException ex) {
                    // 不记录可能包含SQL参数或密文的异常；租约到期后恢复。
                    log.warn("验证码邮件任务数据库操作暂不可用，taskId={}", id);
                }
            }
        } catch (DataAccessException ex) {
            log.warn("验证码邮件任务扫描暂不可用");
        }
    }

    void deliver(String id) {
        String token = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime startedAt = now();
        if (tasks.claim(id, token, startedAt, startedAt.plusSeconds(properties.leaseSeconds())) != 1) {
            return;
        }
        EmailDeliveryTask task = tasks.selectById(id);
        if (task == null || task.getStatus() != EmailDeliveryTask.Status.SENDING
                || !token.equals(task.getLeaseToken())) {
            return;
        }
        EmailChallenge challenge = challenges.selectById(task.getChallengeId());
        if (challenge == null || challenge.getStatus() != EmailChallenge.Status.ACTIVE
                || !challenge.getExpiresAt().isAfter(now())) {
            tasks.finish(id, token, EmailDeliveryTask.Status.CANCELLED, "CHALLENGE_UNAVAILABLE", now());
            return;
        }
        if (task.getAttemptCount() > properties.maxDeliveryAttempts()) {
            tasks.finish(id, token, EmailDeliveryTask.Status.FAILED, "ATTEMPTS_EXHAUSTED", now());
            return;
        }
        String code;
        try {
            code = crypto.decrypt(task.getEncryptionKeyId(), id, challenge.getId(),
                    task.getCodeCiphertext(), task.getEncryptionIv());
        } catch (IllegalArgumentException ex) {
            tasks.finish(id, token, EmailDeliveryTask.Status.FAILED, "CODE_UNAVAILABLE", now());
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.fromAddress());
        message.setTo(challenge.getDeliveryEmail());
        String purpose = challenge.getPurpose() == EmailChallengePurpose.REGISTER ? "注册" : "找回密码";
        message.setSubject("免费技术社区：" + purpose + "验证码");
        message.setText("你的" + purpose + "验证码为：" + code
                + "\n失效时间：" + challenge.getExpiresAt().toInstant(ZoneOffset.UTC)
                + "\n请在当前页面提交本次验证码。重新申请后，旧验证码失效。"
                + "\n如果并非本人操作，请忽略此邮件。");
        try {
            sender.send(message);
        } catch (MailException ex) {
            LocalDateTime failedAt = now();
            long delay = Math.min(60L, properties.retryBaseSeconds()
                    * (1L << Math.min(10, task.getAttemptCount() - 1)));
            LocalDateTime retryAt = failedAt.plusSeconds(delay);
            if (task.getAttemptCount() >= properties.maxDeliveryAttempts()
                    || !retryAt.isBefore(challenge.getExpiresAt())) {
                tasks.finish(id, token, EmailDeliveryTask.Status.FAILED, "SMTP_FAILED", failedAt);
            } else {
                tasks.retry(id, token, failedAt, retryAt);
            }
            return;
        }
        // 只有相同认领令牌才能回写；失去租约的执行者不能覆盖新状态。
        tasks.markSent(id, token, now());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
