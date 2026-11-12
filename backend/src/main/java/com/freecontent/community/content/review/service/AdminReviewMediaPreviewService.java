package com.freecontent.community.content.review.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.media.dto.MediaAccess;

public interface AdminReviewMediaPreviewService {
    MediaAccess getAccess(String reviewId, String assetId, SessionPrincipal principal, String sourceIp, String requestId);
}
