package com.freecontent.community.auth.dto;

import java.time.Instant;

public record ChallengeReceipt(
        String challengeId,
        Instant expiresAt,
        Instant resendAfter
) {
}
