package com.freecontent.community.content.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.dto.CreateContentRequest;

public interface ContentCreationService {
    ContentWorkspace create(CreateContentRequest request, String key, SessionPrincipal principal, String sourceIp);
}
