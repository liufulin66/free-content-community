package com.freecontent.community.media.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.media.dto.CreateUploadRequest;
import com.freecontent.community.media.dto.UploadSession;

public interface UploadService {

    UploadSession create(CreateUploadRequest request, String idempotencyKey, SessionPrincipal principal, String sourceIp);
}
