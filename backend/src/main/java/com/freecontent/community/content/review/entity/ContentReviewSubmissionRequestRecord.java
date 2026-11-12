package com.freecontent.community.content.review.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter @TableName("content_review_submission_request")
public class ContentReviewSubmissionRequestRecord {
    private String id;
    private String createdBy;
    private String keyHash;
    private String fingerprint;
    private String state;
    private String contentId;
    private String reviewId;
    private String resultSnapshot;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
