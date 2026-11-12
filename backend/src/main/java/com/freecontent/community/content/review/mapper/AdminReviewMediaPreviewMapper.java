package com.freecontent.community.content.review.mapper;

import com.freecontent.community.content.model.ContentKind;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AdminReviewMediaPreviewMapper {
    record Content(String id, String authorId, ContentKind kind) { }
    record Review(String id, String contentId, String revisionId) { }

    @Select("SELECT content_id FROM content_review WHERE id=#{review}")
    String findContentId(@Param("review") String review);
    @Select("SELECT id,author_id,kind FROM community_content WHERE id=#{content} FOR SHARE")
    Content lockContent(@Param("content") String content);
    @Select("SELECT id,content_id,revision_id FROM content_review WHERE id=#{review} AND content_id=#{content} FOR SHARE")
    Review lockReview(@Param("review") String review, @Param("content") String content);
    @Select("SELECT status FROM content_revision WHERE id=#{revision} AND content_id=#{content} FOR SHARE")
    String lockRevisionStatus(@Param("content") String content, @Param("revision") String revision);
    @Select("SELECT usage_type FROM content_revision_asset WHERE revision_id=#{revision} AND asset_id=#{asset} ORDER BY usage_type FOR SHARE")
    List<String> findUsages(@Param("revision") String revision, @Param("asset") String asset);

    @Insert("""
            INSERT INTO content_review_media_preview_audit(id,actor_id,review_id,content_id,revision_id,asset_id,action,request_id,occurred_at,expires_at)
            VALUES(#{id},#{actor},#{review},#{content},#{revision},#{asset},'ADMIN_REVIEW_MEDIA_PREVIEW',#{requestId},#{issued},#{expires})
            """)
    int insertAudit(@Param("id") String id, @Param("actor") String actor, @Param("review") String review, @Param("content") String content,
            @Param("revision") String revision, @Param("asset") String asset, @Param("requestId") String requestId,
            @Param("issued") LocalDateTime issued, @Param("expires") LocalDateTime expires);
}
