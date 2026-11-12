package com.freecontent.community.content.browse.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.browse.dto.ContentPageView;

public interface PublicContentListService {
    ContentPageView listPublishedContents(String cursor, String limit, String kind, String tagId, SessionPrincipal viewer, String sourceIp);
}
