package com.freecontent.community.content.mapper;

import com.freecontent.community.content.model.ContentKind;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.time.LocalDateTime;

@Mapper
public interface ContentMediaPreviewMapper {
    record Content(String id,ContentKind kind,int version) { }
    @Select("SELECT id,kind,version FROM community_content WHERE id=#{id} AND author_id=#{owner} FOR SHARE")
    Content findOwned(@Param("id") String id,@Param("owner") String owner);
    @Select("SELECT id FROM content_revision WHERE id=#{revision} AND content_id=#{content} FOR SHARE")
    String findRevision(@Param("content") String content,@Param("revision") String revision);
    @Select("SELECT usage_type FROM content_revision_asset WHERE revision_id=#{revision} AND asset_id=#{asset} ORDER BY usage_type FOR SHARE")
    List<String> findUsages(@Param("revision") String revision,@Param("asset") String asset);
    @Insert("""
            INSERT INTO content_media_preview_audit(id,actor_id,content_id,revision_id,asset_id,action,request_id,occurred_at,expires_at)
            VALUES(#{id},#{actor},#{content},#{revision},#{asset},'CREATOR_REVISION_MEDIA_PREVIEW',#{requestId},#{issuedAt},#{expiresAt})
            """)
    int insertAudit(@Param("id") String id,@Param("actor") String actor,@Param("content") String content,@Param("revision") String revision,
            @Param("asset") String asset,@Param("requestId") String requestId,@Param("issuedAt") LocalDateTime issuedAt,@Param("expiresAt") LocalDateTime expiresAt);
}
