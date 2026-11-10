package com.freecontent.community.content.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.dto.WorkspacePage;

public interface ContentWorkspaceService {
    ContentWorkspace getMyWorkspace(String contentId, SessionPrincipal principal, String sourceIp);
    WorkspacePage listMyContents(String page,String pageSize,String lifecycle,String publicationStatus,String kind,
            SessionPrincipal principal,String sourceIp);
}
