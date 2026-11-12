package com.freecontent.community.content.review.service;

import com.freecontent.community.content.review.dto.CancelReviewRequest;
import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.dto.*;

public interface ReviewCancellationService {
    ReviewResult cancel(String contentId, String reviewId, CancelReviewRequest input, SessionPrincipal principal, String sourceIp, String requestId);
}
