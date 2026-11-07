package com.freecontent.community.media.service;

import com.freecontent.community.auth.security.SessionPrincipal;

public interface UploadCancellationService {
    void cancel(String uploadId, Integer expectedVersion, SessionPrincipal principal, String sourceIp);
}
