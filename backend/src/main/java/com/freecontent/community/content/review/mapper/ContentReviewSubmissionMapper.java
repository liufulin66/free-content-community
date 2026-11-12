package com.freecontent.community.content.review.mapper;

import com.freecontent.community.content.model.persistence.ReviewInsert;
import com.freecontent.community.content.model.persistence.ReviewEventReference;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface ContentReviewSubmissionMapper {
    @Select("SELECT id FROM content_review WHERE pending_content_id=#{content}")
    String findPending(@Param("content") String content);
    @Select("SELECT COUNT(*) FROM content_review WHERE id=#{review} AND content_id=#{content} AND revision_id=#{revision}")
    int countReview(@Param("review") String review, @Param("content") String content, @Param("revision") String revision);
    @Insert("""
            INSERT INTO content_review(id,content_id,revision_id,purpose,state,version,submitted_at)
            VALUES(#{review.id},#{review.contentId},#{review.revisionId},#{review.purpose},'PENDING',1,#{now})
            """)
    int insertReview(@Param("review") ReviewInsert review, @Param("now") LocalDateTime now);

    @Update("UPDATE content_revision SET status='IN_REVIEW',updated_at=#{now} WHERE id=#{revision} AND content_id=#{content} AND status='DRAFT'")
    int freeze(@Param("content") String content, @Param("revision") String revision, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE community_content SET version=version+1,active_review_id=#{review},updated_at=#{now}
            WHERE id=#{content} AND author_id=#{owner} AND version=#{version} AND version<2147483647
                AND lifecycle='ACTIVE' AND working_revision_id=#{revision} AND active_review_id IS NULL
            """)
    int attach(@Param("content") String content, @Param("owner") String owner, @Param("version") int version,
            @Param("revision") String revision, @Param("review") String review, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO content_review_outbox(id,content_id,revision_id,review_id,content_version,event_type,payload,state,created_at)
            VALUES(#{id},#{review.contentId},#{review.revisionId},#{review.id},#{version},'CONTENT_REVIEW_SUBMITTED',#{payload},'PENDING',#{now})
            """)
    int insertEvent(@Param("id") String id, @Param("review") ReviewEventReference review, @Param("version") int version,
            @Param("payload") String payload, @Param("now") LocalDateTime now);
}
