package com.freecontent.community.content.review.mapper;

import com.freecontent.community.content.model.persistence.ReviewRow;
import com.freecontent.community.content.model.persistence.ContentRow;
import com.freecontent.community.content.model.persistence.ReviewEventReference;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface ReviewDecisionMapper {
    record Audit(String id, String actor, String review, String content, String revision, String decision, String reason,
            int beforeContentVersion, int afterContentVersion, int beforeReviewVersion, int afterReviewVersion, String requestId, LocalDateTime occurredAt) { }
    @Select("SELECT content_id FROM content_review WHERE id=#{review}")
    String findContentId(@Param("review") String review);
    @Select("""
            SELECT id,author_id,kind,lifecycle,publication_status,version,working_revision_id,published_revision_id,
                   active_review_id,created_at,updated_at,deleted_at FROM community_content WHERE id=#{content} FOR UPDATE
            """)
    ContentRow lockContent(@Param("content") String content);
    @Select("""
            SELECT id,content_id,revision_id,purpose,state,version,submitted_at,decided_at,reviewer_id,reason
            FROM content_review WHERE id=#{review} AND content_id=#{content} FOR UPDATE
            """)
    ReviewRow lockReview(@Param("review") String review, @Param("content") String content);
    @Select("SELECT status FROM content_revision WHERE id=#{revision} AND content_id=#{content} FOR UPDATE")
    String lockRevisionStatus(@Param("revision") String revision, @Param("content") String content);
    @Update("""
            UPDATE content_revision SET status=#{state},updated_at=#{now}
            WHERE id=#{revision} AND content_id=#{content} AND status='IN_REVIEW'
            """)
    int decideRevision(@Param("revision") String revision, @Param("content") String content, @Param("state") String state, @Param("now") LocalDateTime now);
    @Update("""
            UPDATE content_review SET state=#{state},version=version+1,decided_at=#{now},reviewer_id=#{actor},reason=#{reason}
            WHERE id=#{review} AND content_id=#{content} AND revision_id=#{revision} AND state='PENDING' AND version=#{version} AND version<2147483647
            """)
    int decideReview(@Param("review") String review, @Param("content") String content, @Param("revision") String revision, @Param("version") int version,
            @Param("state") String state, @Param("actor") String actor, @Param("reason") String reason, @Param("now") LocalDateTime now);
    @Update("""
            <script> UPDATE community_content SET version=version+1,working_revision_id=NULL,active_review_id=NULL,updated_at=#{now}
            <if test="approved"> ,published_revision_id=#{revision},publication_status='PUBLISHED',
                first_published_at=COALESCE(first_published_at,#{now}),published_at=#{now} </if>
            WHERE id=#{content} AND version=#{version} AND version&lt;2147483647 AND lifecycle='ACTIVE'
                AND working_revision_id=#{revision} AND active_review_id=#{review} </script>
            """)
    int updateContent(@Param("content") String content, @Param("version") int version, @Param("revision") String revision,
            @Param("review") String review, @Param("approved") boolean approved, @Param("now") LocalDateTime now);
    @Insert("""
            INSERT INTO content_review_decision_audit(id,actor_id,review_id,content_id,revision_id,decision,reason,
                before_content_version,after_content_version,before_review_version,after_review_version,request_id,occurred_at)
            VALUES(#{a.id},#{a.actor},#{a.review},#{a.content},#{a.revision},#{a.decision},#{a.reason},
                #{a.beforeContentVersion},#{a.afterContentVersion},#{a.beforeReviewVersion},#{a.afterReviewVersion},#{a.requestId},#{a.occurredAt})
            """)
    int insertAudit(@Param("a") Audit audit);
    @Insert("""
            INSERT INTO content_review_outbox(id,content_id,revision_id,review_id,content_version,event_type,payload,state,created_at)
            VALUES(#{id},#{review.contentId},#{review.revisionId},#{review.id},#{version},#{type},#{payload},'PENDING',#{now})
            """)
    int insertEvent(@Param("id") String id, @Param("review") ReviewEventReference review, @Param("version") int version,
            @Param("type") String type, @Param("payload") String payload, @Param("now") LocalDateTime now);
}
