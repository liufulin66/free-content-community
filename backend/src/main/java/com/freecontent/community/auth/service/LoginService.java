package com.freecontent.community.auth.service;

import com.freecontent.community.auth.dto.LoginRequest;
import com.freecontent.community.auth.dto.LoginResult;

/** 密码登录并创建会话的业务接口。 */
public interface LoginService {
    LoginResult login(LoginRequest request, String sourceIp);
}
