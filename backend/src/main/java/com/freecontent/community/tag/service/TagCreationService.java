package com.freecontent.community.tag.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.tag.dto.*;

public interface TagCreationService {
    TagView create(TagWriteRequest request, String key, SessionPrincipal principal, String sourceIp);
}
