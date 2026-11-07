package com.freecontent.community.auth.job;

import com.freecontent.community.auth.AuthTestSupport;
import com.freecontent.community.auth.entity.EmailChallenge;
import com.freecontent.community.auth.entity.EmailDeliveryTask;
import com.freecontent.community.auth.mapper.EmailChallengeMapper;
import com.freecontent.community.auth.mapper.EmailDeliveryTaskMapper;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import com.freecontent.community.auth.security.EmailChallengeCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailDeliveryWorkerTest {

    private final EmailDeliveryTaskMapper tasks = mock(EmailDeliveryTaskMapper.class);
    private final EmailChallengeMapper challenges = mock(EmailChallengeMapper.class);
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final EmailChallengeCrypto crypto = new EmailChallengeCrypto(AuthTestSupport.properties());
    private final Instant instant = Instant.parse("2026-11-05T00:00:00Z");
    private final EmailDeliveryWorker worker = new EmailDeliveryWorker(tasks, challenges, crypto,
            sender, AuthTestSupport.properties(), Clock.fixed(instant, ZoneOffset.UTC));
    private final EmailDeliveryTask task = new EmailDeliveryTask();
    private final EmailChallenge challenge = new EmailChallenge();

    @BeforeEach
    void prepareClaim() {
        var encrypted = crypto.encrypt("task1", "challenge1", "000123");
        task.setId("task1");
        task.setChallengeId("challenge1");
        task.setStatus(EmailDeliveryTask.Status.SENDING);
        task.setAttemptCount(0);
        task.setCodeCiphertext(encrypted.ciphertext());
        task.setEncryptionIv(encrypted.iv());
        task.setEncryptionKeyId(encrypted.keyId());
        challenge.setId("challenge1");
        challenge.setStatus(EmailChallenge.Status.ACTIVE);
        challenge.setPurpose(EmailChallengePurpose.REGISTER);
        challenge.setDeliveryEmail("a@example.test");
        challenge.setExpiresAt(LocalDateTime.ofInstant(instant.plusSeconds(600), ZoneOffset.UTC));
        when(tasks.claim(eq("task1"), anyString(), any(), any())).thenAnswer(invocation -> {
            task.setLeaseToken(invocation.getArgument(1));
            task.setAttemptCount(task.getAttemptCount() + 1);
            return 1;
        });
        when(tasks.selectById("task1")).thenReturn(task);
        when(challenges.selectById("challenge1")).thenReturn(challenge);
    }

    @Test
    void sendsOriginalCodeAndMarksResultWithOwnershipToken() {
        worker.deliver("task1");
        verify(sender).send(argThat((SimpleMailMessage message) ->
                message.getText() != null && message.getText().contains("000123")));
        verify(tasks).markSent(eq("task1"), eq(task.getLeaseToken()), any());
    }

    @Test
    void cancelledChallengeIsNotSent() {
        challenge.setStatus(EmailChallenge.Status.INVALIDATED);
        worker.deliver("task1");
        verifyNoInteractions(sender);
        verify(tasks).finish(eq("task1"), anyString(), eq(EmailDeliveryTask.Status.CANCELLED),
                eq("CHALLENGE_UNAVAILABLE"), any());
    }

    @Test
    void lostOwnershipStopsBeforeSending() {
        when(tasks.claim(eq("task1"), anyString(), any(), any())).thenAnswer(invocation -> {
            task.setLeaseToken("another-owner");
            return 1;
        });
        worker.deliver("task1");
        verifyNoInteractions(sender);
        verify(tasks, never()).markSent(anyString(), anyString(), any());
    }

    @Test
    void transientFailureSchedulesRetryWithSameStoredCode() {
        doThrow(new MailSendException("SMTP unavailable")).when(sender).send(any(SimpleMailMessage.class));
        worker.deliver("task1");
        verify(tasks).retry(eq("task1"), eq(task.getLeaseToken()), any(),
                eq(LocalDateTime.ofInstant(instant.plusSeconds(5), ZoneOffset.UTC)));
        assertTrue(crypto.decrypt(task.getEncryptionKeyId(), "task1", "challenge1",
                task.getCodeCiphertext(), task.getEncryptionIv()).equals("000123"));
    }

    @ParameterizedTest
    @CsvSource({"DEFAULT,8", "200,2"})
    void unresponsiveSmtpServerTimesOutAndSchedulesRetry(String timeoutOverride, long deadlineSeconds) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(2000);
            var runner = new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                    .withPropertyValues("spring.mail.host=" + server.getInetAddress().getHostAddress(),
                            "spring.mail.port=" + server.getLocalPort());
            if (!"DEFAULT".equals(timeoutOverride)) {
                runner = runner.withPropertyValues("MAIL_SMTP_READ_TIMEOUT_MILLIS=" + timeoutOverride);
            }
            runner.run(context -> {
                var actualWorker = new EmailDeliveryWorker(tasks, challenges, crypto,
                        context.getBean(JavaMailSender.class), AuthTestSupport.properties(),
                        Clock.fixed(instant, ZoneOffset.UTC));
                var delivery = executor.submit(() -> actualWorker.deliver("task1"));
                // 接收TCP连接但不返回SMTP问候，模拟依赖存活却无响应。
                try (Socket peer = server.accept()) {
                    assertDoesNotThrow(() -> delivery.get(deadlineSeconds, TimeUnit.SECONDS),
                            "SMTP无响应时必须退出发送，不能一直占用后台调度线程");
                    verify(tasks).retry(eq("task1"), eq(task.getLeaseToken()), any(), any());
                    verify(tasks, never()).markSent(anyString(), anyString(), any());
                } finally {
                    delivery.cancel(true);
                }
            });
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void exhaustedCrashRecoveryDoesNotSendAgain() {
        task.setAttemptCount(3);
        worker.deliver("task1");
        verifyNoInteractions(sender);
        verify(tasks).finish(eq("task1"), anyString(), eq(EmailDeliveryTask.Status.FAILED),
                eq("ATTEMPTS_EXHAUSTED"), any());
    }
}
