package com.freecontent.community.content.browse.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.browse.dto.ContentPageView;

public interface UserContentListService {
    ContentPageView listUserContents(String userId, String cursor, String limit, SessionPrincipal viewer, String sourceIp);
}
