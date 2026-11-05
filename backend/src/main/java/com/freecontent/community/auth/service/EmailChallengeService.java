package com.freecontent.community.auth.service;

import com.freecontent.community.auth.dto.ChallengeReceipt;
import com.freecontent.community.auth.dto.CreateEmailChallengeRequest;

/** 邮箱验证码申请的业务接口。 */
public interface EmailChallengeService {
    ChallengeReceipt create(CreateEmailChallengeRequest request, String sourceIp);
}
