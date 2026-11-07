package com.freecontent.community.media.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.media.dto.UploadSession;

public interface UploadQueryService {
    UploadSession getMyUpload(String uploadId, SessionPrincipal principal, String sourceIp);
}
