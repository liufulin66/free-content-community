package com.freecontent.community.auth.service.transaction;

import com.freecontent.community.auth.entity.EmailChallenge;
import com.freecontent.community.auth.entity.EmailDeliveryTask;
import com.freecontent.community.auth.mapper.EmailChallengeMapper;
import com.freecontent.community.auth.mapper.EmailDeliveryTaskMapper;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 显式运行：需要真实MySQL及本地环境变量。
 * 只创建带随机邮箱的本测试记录，并在finally中清理。
 */
@SpringBootTest(properties = "app.auth.email-challenge.delivery-enabled=false")
class EmailChallengeTransactionIT {

    @Autowired
    private EmailChallengeTransactionService transactions;

    @Autowired
    private EmailChallengeMapper challenges;

    @MockitoBean
    private EmailDeliveryTaskMapper tasks;

    @Test
    void taskInsertFailureRollsBackReplacementAndNewChallenge() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String email = "transaction-" + UUID.randomUUID().toString().replace("-", "") + "@example.test";
        EmailChallenge old = challenge(email, now.minusSeconds(120));
        EmailChallenge next = challenge(email, now);
        try {
            challenges.insert(old);
            when(tasks.insert(any(EmailDeliveryTask.class)))
                    .thenThrow(new DataIntegrityViolationException("injected task persistence failure"));
            assertThrows(DataIntegrityViolationException.class,
                    () -> transactions.save(next, new EmailDeliveryTask()));
            assertNull(challenges.selectById(next.getId()), "新申请必须回滚");
            assertEquals(EmailChallenge.Status.ACTIVE,
                    challenges.selectById(old.getId()).getStatus(), "旧申请失效必须回滚");
        } finally {
            challenges.deleteById(next.getId());
            challenges.deleteById(old.getId());
        }
    }

    private EmailChallenge challenge(String email, LocalDateTime createdAt) {
        EmailChallenge result = new EmailChallenge();
        result.setId(UUID.randomUUID().toString().replace("-", ""));
        result.setEmailNormalized(email);
        result.setDeliveryEmail(email);
        result.setPurpose(EmailChallengePurpose.REGISTER);
        result.setCodeMac(new byte[32]);
        result.setMacKeyId("integration-test");
        result.setStatus(EmailChallenge.Status.ACTIVE);
        result.setCreatedAt(createdAt);
        result.setUpdatedAt(createdAt);
        result.setExpiresAt(createdAt.plusSeconds(600));
        result.setResendAfter(createdAt.plusSeconds(60));
        return result;
    }
}
