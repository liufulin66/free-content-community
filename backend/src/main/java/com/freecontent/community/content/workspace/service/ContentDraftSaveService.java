package com.freecontent.community.content.workspace.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.workspace.dto.EditDraftRequest;

public interface ContentDraftSaveService {
    ContentWorkspace save(String contentId,EditDraftRequest patch,SessionPrincipal principal,String sourceIp);
}
