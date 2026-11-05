package com.freecontent.community.auth.dto;

import java.time.Instant;//表示时间线上的一个确定时刻

public record ChallengeReceipt(
        String challengeId,//验证码ID
        Instant expiresAt,//验证码过期时间
        Instant resendAfter//重新发送验证码的时间
) {
}