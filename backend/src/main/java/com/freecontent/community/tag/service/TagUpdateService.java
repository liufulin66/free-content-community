package com.freecontent.community.tag.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.tag.dto.TagUpdateRequest;
import com.freecontent.community.tag.dto.TagView;

public interface TagUpdateService {
    TagView update(String tagId, TagUpdateRequest patch, SessionPrincipal principal, String sourceIp);
}
