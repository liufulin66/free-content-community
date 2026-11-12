package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.media.dto.MediaAccess;

public interface ContentMediaPreviewService {
    MediaAccess getAccess(String contentId,String revisionId,String assetId,SessionPrincipal principal,String sourceIp,String requestId);
}
