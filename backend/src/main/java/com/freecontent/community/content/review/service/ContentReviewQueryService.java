package com.freecontent.community.content.review.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.review.dto.ReviewResult;

public interface ContentReviewQueryService {
    ReviewResult getMyReview(String contentId, String reviewId, SessionPrincipal principal, String sourceIp);
}
