package com.freecontent.community.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth.email-challenge")
public record EmailChallengeProperties(
        long ttlSeconds,
        long resendIntervalSeconds,
        int emailHourlyLimit,
        int ipHourlyLimit,
        int maxDeliveryAttempts,
        long pollDelayMillis,
        long leaseSeconds,
        long retryBaseSeconds,
        boolean deliveryEnabled,
        String fromAddress,
        String macKeyId,
        String macKeyBase64,
        String encryptionKeyId,
        String encryptionKeyBase64,
        String redisKeyPrefix
) {
    public EmailChallengeProperties {
        if (ttlSeconds < 1 || ttlSeconds > 86400
                || resendIntervalSeconds < 1 || resendIntervalSeconds > ttlSeconds
                || emailHourlyLimit < 1 || ipHourlyLimit < 1
                || maxDeliveryAttempts < 1 || maxDeliveryAttempts > 10
                || pollDelayMillis < 100 || leaseSeconds < 30 || retryBaseSeconds < 1) {
            throw new IllegalArgumentException("邮箱验证码的期限、配额或任务参数不合法");
        }
        if (fromAddress == null || !fromAddress.matches("[^\\s@]+@[^\\s@]+")
                || !validKeyId(macKeyId) || !validKeyId(encryptionKeyId)
                || redisKeyPrefix == null || redisKeyPrefix.isBlank()
                || redisKeyPrefix.contains("{") || redisKeyPrefix.contains("}")) {
            throw new IllegalArgumentException("邮箱验证码的发件地址、密钥标识或Redis前缀不合法");
        }
    }

    private static boolean validKeyId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }

    @Override
    public String toString() {
        return "EmailChallengeProperties[ttlSeconds=" + ttlSeconds
                + ", resendIntervalSeconds=" + resendIntervalSeconds + ", keys=REDACTED]";
    }
}
