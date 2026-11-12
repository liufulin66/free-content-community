package com.freecontent.community.content.browse.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.media.dto.MediaAccess;

public interface PublishedMediaAccessService {
    MediaAccess getAccess(String contentId, String assetId, SessionPrincipal viewer, String sourceIp);
}
