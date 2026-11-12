package com.freecontent.community.content.review.service;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.review.dto.ReviewPage;
import com.freecontent.community.content.review.dto.AdminReviewDetail;

public interface AdminReviewQueryService {
    ReviewPage list(String page, String pageSize, String state, String purpose, SessionPrincipal principal, String sourceIp);
    AdminReviewDetail get(String reviewId, SessionPrincipal principal, String sourceIp);
}
