package com.freecontent.community.content.review.mapper;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface ContentReviewQueryMapper {
    record OwnedReviewRow(String id, String contentId, String revisionId, String purpose, String state, int version,
            LocalDateTime submittedAt, LocalDateTime decidedAt, String reviewerId, String reason, int contentVersion) { }

    /** 单条关联查询读取指定本人审核及聚合当前版本，不依赖活动审核指针。 */
    @Select("""
            SELECT r.id,r.content_id,r.revision_id,r.purpose,r.state,r.version,r.submitted_at,
                   r.decided_at,r.reviewer_id,r.reason,c.version AS content_version
            FROM content_review r JOIN community_content c ON c.id=r.content_id
            WHERE c.id=#{contentId} AND c.author_id=#{owner} AND r.id=#{reviewId}
            """)
    OwnedReviewRow findOwned(@Param("contentId") String contentId, @Param("reviewId") String reviewId, @Param("owner") String owner);
}
