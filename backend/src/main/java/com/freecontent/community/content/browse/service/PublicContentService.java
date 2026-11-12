package com.freecontent.community.content.browse.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.browse.dto.PublicContentView;

public interface PublicContentService {
    PublicContentView getPublishedContent(String contentId, SessionPrincipal viewer, String sourceIp);
}
