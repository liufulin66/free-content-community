package com.freecontent.community.media.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.media.dto.CompleteUploadRequest;
import com.freecontent.community.media.dto.TaskReceipt;

public interface UploadCompletionService {
    TaskReceipt complete(String uploadId, CompleteUploadRequest request, String key,
                         SessionPrincipal principal, String sourceIp);
}
