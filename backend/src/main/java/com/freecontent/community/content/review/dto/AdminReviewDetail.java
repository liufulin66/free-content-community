package com.freecontent.community.content.review.dto;

import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.dto.RevisionView;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"review", "content", "revision"})
public record AdminReviewDetail(ReviewView review, ContentWorkspace content, RevisionView revision) { }
