package com.freecontent.community.auth.service;

import com.freecontent.community.auth.dto.RegisterRequest;
import com.freecontent.community.user.dto.CurrentUser;

/** 邮箱验证码注册的业务接口。 */
public interface RegistrationService {
    CurrentUser register(RegisterRequest request, String sourceIp);
}
