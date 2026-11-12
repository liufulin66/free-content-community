package com.freecontent.community.content.workspace.mapper;

import com.freecontent.community.content.model.persistence.ContentInsert;
import com.freecontent.community.content.model.persistence.RevisionInsert;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface ContentCreationMapper {
    @Insert("""
            INSERT INTO community_content(id,author_id,kind,lifecycle,publication_status,version,created_at,updated_at)
            VALUES(#{view.id},#{view.authorId},#{view.kind},'ACTIVE','UNPUBLISHED',1,#{now},#{now})
            """)
    int insertContent(@Param("view") ContentInsert view, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO content_revision(id,content_id,revision_number,status,title,body_markdown,created_at,updated_at)
            VALUES(#{draft.id},#{draft.contentId},1,'DRAFT',#{draft.title},#{draft.bodyMarkdown},#{now},#{now})
            """)
    int insertRevision(@Param("draft") RevisionInsert draft, @Param("now") LocalDateTime now);

    @Update("UPDATE community_content SET working_revision_id=#{revisionId} WHERE id=#{id} AND working_revision_id IS NULL AND version=1")
    int attachWorkingRevision(@Param("id") String id, @Param("revisionId") String revisionId);

    @Insert("INSERT INTO content_revision_asset(revision_id,asset_id,usage_type,position) VALUES(#{revision},#{asset},#{usage},#{position})")
    int insertAsset(@Param("revision") String revision, @Param("asset") String asset,
            @Param("usage") String usage, @Param("position") int position);

    @Insert("INSERT INTO content_revision_tag(revision_id,tag_id,position) VALUES(#{revision},#{tag},#{position})")
    int insertTag(@Param("revision") String revision, @Param("tag") String tag, @Param("position") int position);

    @Select("SELECT author_id FROM community_content WHERE id=#{id}")
    String findAuthor(@Param("id") String id);
}
