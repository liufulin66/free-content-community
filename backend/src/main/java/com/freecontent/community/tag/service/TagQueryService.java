package com.freecontent.community.tag.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.tag.dto.TagView;
import com.freecontent.community.tag.dto.TagPageView;
import com.freecontent.community.tag.dto.TagListView;
import java.util.Set;

public interface TagQueryService {
    TagListView listActiveTags(String sourceIp, Set<String> queryParameters);
    TagView getAdminTag(String tagId, SessionPrincipal principal, String sourceIp);
    TagPageView listAdminTags(String page, String pageSize, SessionPrincipal principal, String sourceIp);
}
