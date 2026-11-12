package com.freecontent.community.content.review.mapper;

import com.freecontent.community.content.model.persistence.ReviewRow;
import com.freecontent.community.content.model.persistence.ContentRow;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface ReviewCancellationMapper {
    record Audit(String id, String actor, String content, String review, String revision, int beforeContentVersion, int afterContentVersion,
            int beforeReviewVersion, int afterReviewVersion, String requestId, LocalDateTime occurredAt) { }
    @Select("""
            SELECT id,author_id,kind,lifecycle,publication_status,version,working_revision_id,published_revision_id,
                   active_review_id,created_at,updated_at,deleted_at FROM community_content WHERE id=#{content} AND author_id=#{owner} FOR UPDATE
            """)
    ContentRow lockOwned(@Param("content") String content, @Param("owner") String owner);
    @Select("""
            SELECT id,content_id,revision_id,purpose,state,version,submitted_at,decided_at,reviewer_id,reason
            FROM content_review WHERE id=#{review} AND content_id=#{content} FOR UPDATE
            """)
    ReviewRow lockReview(@Param("review") String review, @Param("content") String content);
    @Select("""
            SELECT COUNT(*) FROM content_review_cancellation_audit
            WHERE actor_id=#{actor} AND content_id=#{content} AND review_id=#{review} AND revision_id=#{revision}
            """)
    int countOwnCancellation(@Param("actor") String actor, @Param("content") String content, @Param("review") String review, @Param("revision") String revision);
    @Select("SELECT status FROM content_revision WHERE id=#{revision} AND content_id=#{content} FOR UPDATE")
    String lockRevisionStatus(@Param("revision") String revision, @Param("content") String content);
    @Update("UPDATE content_revision SET status='CANCELLED',updated_at=#{now} WHERE id=#{revision} AND content_id=#{content} AND status='IN_REVIEW'")
    int cancelRevision(@Param("revision") String revision, @Param("content") String content, @Param("now") LocalDateTime now);
    @Update("""
            UPDATE content_review SET state='CANCELLED',version=version+1,decided_at=#{now},reviewer_id=NULL,reason=NULL
            WHERE id=#{review} AND content_id=#{content} AND revision_id=#{revision} AND state='PENDING' AND version=#{version} AND version<2147483647
            """)
    int cancelReview(@Param("review") String review, @Param("content") String content, @Param("revision") String revision, @Param("version") int version, @Param("now") LocalDateTime now);
    @Update("""
            UPDATE community_content SET version=version+1,updated_at=#{now},working_revision_id=NULL,active_review_id=NULL
            WHERE id=#{content} AND author_id=#{owner} AND version=#{version} AND version<2147483647 AND lifecycle='ACTIVE'
                  AND working_revision_id=#{revision} AND active_review_id=#{review}
            """)
    int updateContent(@Param("content") String content, @Param("owner") String owner, @Param("version") int version,
            @Param("revision") String revision, @Param("review") String review, @Param("now") LocalDateTime now);
    @Insert("""
            INSERT INTO content_review_cancellation_audit(id,actor_id,content_id,review_id,revision_id,before_content_version,after_content_version,
                before_review_version,after_review_version,request_id,occurred_at)
            VALUES(#{a.id},#{a.actor},#{a.content},#{a.review},#{a.revision},#{a.beforeContentVersion},#{a.afterContentVersion},
                #{a.beforeReviewVersion},#{a.afterReviewVersion},#{a.requestId},#{a.occurredAt})
            """)
    int insertAudit(@Param("a") Audit audit);
    @Insert("""
            INSERT INTO content_review_outbox(id,content_id,revision_id,review_id,content_version,event_type,payload,state,created_at)
            VALUES(#{id},#{content},#{revision},#{review},#{version},'CONTENT_REVIEW_CANCELLED',#{payload},'PENDING',#{now})
            """)
    int insertEvent(@Param("id") String id, @Param("content") String content, @Param("revision") String revision, @Param("review") String review,
            @Param("version") int version, @Param("payload") String payload, @Param("now") LocalDateTime now);
}
