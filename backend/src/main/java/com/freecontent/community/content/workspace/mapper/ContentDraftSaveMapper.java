package com.freecontent.community.content.workspace.mapper;

import com.freecontent.community.content.model.persistence.DraftUpdate;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface ContentDraftSaveMapper {
    @Update("""
            UPDATE community_content SET version=version+1,updated_at=#{now}
            WHERE id=#{id} AND author_id=#{owner} AND version=#{version} AND lifecycle='ACTIVE'
                  AND working_revision_id=#{revisionId} AND active_review_id IS NULL
            """)
    int advanceVersion(@Param("id") String id,@Param("owner") String owner,@Param("version") int version,
            @Param("revisionId") String revisionId,@Param("now") LocalDateTime now);
    @Update("""
            UPDATE content_revision SET title=#{input.title},body_markdown=#{input.bodyMarkdown},updated_at=#{now}
            WHERE id=#{id} AND content_id=#{contentId} AND status='DRAFT'
            """)
    int updateDraft(@Param("id") String id,@Param("contentId") String contentId,@Param("input") DraftUpdate input,@Param("now") LocalDateTime now);

    @Delete("DELETE FROM content_revision_asset WHERE revision_id=#{revision} AND usage_type=#{usage}")
    int deleteAssets(@Param("revision") String revision,@Param("usage") String usage);
    @Insert("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(#{revision},#{asset},#{usage},#{position})")
    int insertAsset(@Param("revision") String revision,@Param("asset") String asset,@Param("usage") String usage,@Param("position") int position);
    @Delete("DELETE FROM content_revision_tag WHERE revision_id=#{revision}")
    int deleteTags(@Param("revision") String revision);
    @Insert("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(#{revision},#{tag},#{position})")
    int insertTag(@Param("revision") String revision,@Param("tag") String tag,@Param("position") int position);
}
