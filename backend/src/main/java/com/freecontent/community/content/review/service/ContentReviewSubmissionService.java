package com.freecontent.community.content.review.service;

import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.content.review.dto.SubmitReviewRequest;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.dto.*;

public interface ContentReviewSubmissionService {
    ReviewResult submit(String contentId, SubmitReviewRequest input, String key, SessionPrincipal principal, String sourceIp);
}
