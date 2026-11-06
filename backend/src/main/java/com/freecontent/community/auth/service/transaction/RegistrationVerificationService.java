package com.freecontent.community.auth.service.transaction;

import com.freecontent.community.auth.config.RegistrationProperties;
import com.freecontent.community.auth.dto.RegisterRequest;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 注册入口固定REGISTER用途，复用通用校验及错码计数。 */
@Service
public class RegistrationVerificationService {
    private final EmailChallengeVerificationService verification;
    private final RegistrationProperties properties;

    public RegistrationVerificationService(EmailChallengeVerificationService verification, RegistrationProperties properties) {
        this.verification = verification;
        this.properties = properties;
    }

    @Transactional
    public boolean verify(RegisterRequest request, String normalizedEmail) {
        return verification.verify(request.challengeId(), normalizedEmail, EmailChallengePurpose.REGISTER,
                request.code(), properties.maxCodeFailures());
    }
}
