package com.freecontent.community.content.review.service;

import com.freecontent.community.content.review.dto.ReviewDecisionRequest;
import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.dto.*;

public interface ReviewDecisionService {
    ReviewResult decide(String reviewId, ReviewDecisionRequest input, String key, SessionPrincipal principal, String sourceIp, String requestId);
}
