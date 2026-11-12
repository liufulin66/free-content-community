package com.freecontent.community.content.browse.mapper;

import com.freecontent.community.content.model.ContentKind;
import com.freecontent.community.content.model.persistence.AssetReference;
import com.freecontent.community.content.model.persistence.RevisionRow;
import com.freecontent.community.media.entity.MediaAsset;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 公开签名授权的当前读；内容、修订、引用、素材和标签的锁持续到短事务结束。 */
@Mapper
public interface PublishedMediaAccessMapper {
    record Publication(String id, String authorId, ContentKind kind, String lifecycle, String publicationStatus,
            String publishedRevisionId, LocalDateTime firstPublishedAt, LocalDateTime publishedAt) { }

    @Select("SELECT id,author_id,kind,lifecycle,publication_status,published_revision_id,first_published_at,published_at "
            + "FROM community_content WHERE id=#{content} FOR SHARE")
    Publication lockPublication(@Param("content") String content);

    @Select("SELECT id,content_id,revision_number,status,title,body_markdown,created_at,updated_at "
            + "FROM content_revision WHERE id=#{revision} AND content_id=#{content} FOR SHARE")
    RevisionRow lockRevision(@Param("content") String content, @Param("revision") String revision);

    @Select("SELECT asset_id,usage_type,position FROM content_revision_asset WHERE revision_id=#{revision} ORDER BY usage_type,position FOR SHARE")
    List<AssetReference> lockReferences(@Param("revision") String revision);

    @Select("""
        <script> SELECT * FROM media_asset WHERE owner_id=#{owner} AND id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")"> #{id} </foreach>
        ORDER BY id FOR SHARE </script>
        """)
    List<MediaAsset> lockAssets(@Param("owner") String owner, @Param("ids") List<String> ids);

    @Select("""
        SELECT t.id,t.name,t.description,t.status,t.version FROM content_revision_tag ref
        LEFT JOIN community_tag t ON t.id=ref.tag_id WHERE ref.revision_id=#{revision} ORDER BY ref.position FOR SHARE
        """)
    List<PublicContentMapper.TagRow> lockTags(@Param("revision") String revision);
}
