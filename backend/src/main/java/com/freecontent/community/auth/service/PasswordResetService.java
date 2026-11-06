package com.freecontent.community.auth.service;

import com.freecontent.community.auth.dto.ResetPasswordRequest;

public interface PasswordResetService {
    void reset(ResetPasswordRequest request, String sourceIp);
}
