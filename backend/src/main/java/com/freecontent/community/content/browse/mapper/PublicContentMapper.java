package com.freecontent.community.content.browse.mapper;

import com.freecontent.community.content.model.ContentKind;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PublicContentMapper {
    record PublishedRow(String id, String authorId, ContentKind kind, String lifecycle, String publicationStatus, String publishedRevisionId,
            LocalDateTime firstPublishedAt, LocalDateTime publishedAt, String revisionId, String revisionStatus, String title, String bodyMarkdown) { }
    record AssetRow(String assetId, String usageType, int position, String ownerId, String scene, String state) { }
    record TagRow(String id, String name, String description, String status, int version) { }
    record RevisionAssetRow(String revisionId, String assetId, String usageType, int position, String ownerId, String scene, String state) { }
    record RevisionTagRow(String revisionId, String id, String name, String description, String status, int version) { }
    record AuthorCount(String authorId, long visibleCount) { }

    String COLUMNS = """
        c.id,c.author_id,c.kind,c.lifecycle,c.publication_status,c.published_revision_id,c.first_published_at,c.published_at,
        r.id AS revision_id,r.status AS revision_status,r.title,r.body_markdown
        """;
    // XML fragments are shared by annotated <script> queries; comparison operators must be escaped.
    // Match Java isBlank: exclude FS/GS/RS/US and retain NEL/NBSP/figure-space/narrow-NBSP.
    // CHAR/UTF-8 literals avoid SQL-mode-dependent backslash processing in ICU patterns.
    String VISIBLE = """
        c.lifecycle='ACTIVE' AND c.publication_status='PUBLISHED' AND r.status='APPROVED'
        AND c.first_published_at IS NOT NULL AND c.published_at IS NOT NULL AND c.first_published_at &lt;= c.published_at
        AND REGEXP_LIKE(c.id,'^[A-Za-z0-9_-]{1,64}$','c') AND REGEXP_LIKE(c.author_id,'^[A-Za-z0-9_-]{1,64}$','c')
        AND REGEXP_LIKE(r.id,'^[A-Za-z0-9_-]{1,64}$','c') AND r.body_markdown IS NOT NULL
        AND REGEXP_LIKE(r.title,CONCAT('[^[:space:]',CHAR(28,29,30,31 USING utf8mb4),']|[',CONVERT(0xc285c2a0e28087e280af USING utf8mb4),']'),'c')
        AND NOT EXISTS (SELECT 1 FROM content_revision_asset ref LEFT JOIN media_asset a ON a.id=ref.asset_id
            WHERE ref.revision_id=r.id AND (a.id IS NULL OR a.state!='READY' OR a.owner_id!=c.author_id
              OR (ref.usage_type IN ('COVER','IMAGE') AND a.scene!='CONTENT_IMAGE')
              OR (ref.usage_type='VIDEO' AND (a.scene!='CONTENT_VIDEO' OR c.kind!='VIDEO'))))
        AND (SELECT COUNT(*) FROM content_revision_asset x WHERE x.revision_id=r.id AND x.usage_type='COVER') &lt;= 1
        AND (SELECT COUNT(*) FROM content_revision_asset x WHERE x.revision_id=r.id AND x.usage_type='IMAGE') &lt;= 50
        AND (SELECT COUNT(*) FROM content_revision_tag x WHERE x.revision_id=r.id) &lt;= 5
        AND ((c.kind='ARTICLE' AND NOT EXISTS(SELECT 1 FROM content_revision_asset x WHERE x.revision_id=r.id AND x.usage_type='VIDEO'))
            OR (c.kind='VIDEO' AND (SELECT COUNT(*) FROM content_revision_asset x WHERE x.revision_id=r.id AND x.usage_type='VIDEO')=1))
        """;
    String PUBLISHED_JOIN = " FROM community_content c JOIN content_revision r ON r.id=c.published_revision_id AND r.content_id=c.id ";
    String KEYSET = """
        AND c.first_published_at &lt;= #{snapshot}
        <if test="lastAt != null">
          AND (c.first_published_at &lt; #{lastAt} OR (c.first_published_at=#{lastAt} AND c.id &lt; #{lastId}))
        </if>
        ORDER BY c.first_published_at DESC,c.id DESC LIMIT #{take}
        """;

    @Select("SELECT " + COLUMNS + " FROM community_content c LEFT JOIN content_revision r ON r.id=c.published_revision_id AND r.content_id=c.id WHERE c.id=#{id}")
    PublishedRow findById(@Param("id") String id);

    @Select("""
        SELECT ref.asset_id,ref.usage_type,ref.position,a.owner_id,a.scene,a.state FROM content_revision_asset ref
        LEFT JOIN media_asset a ON a.id=ref.asset_id WHERE ref.revision_id=#{revision} ORDER BY ref.usage_type,ref.position
        """)
    List<AssetRow> findAssets(@Param("revision") String revision);

    @Select("""
        SELECT t.id,t.name,t.description,t.status,t.version FROM content_revision_tag ref
        LEFT JOIN community_tag t ON t.id=ref.tag_id WHERE ref.revision_id=#{revision} ORDER BY ref.position
        """)
    List<TagRow> findTags(@Param("revision") String revision);

    @Select("<script> SELECT COUNT(*) " + PUBLISHED_JOIN + " WHERE c.author_id=#{author} AND " + VISIBLE + " </script>")
    long countVisibleByAuthor(@Param("author") String author);

    @Select("<script> SELECT " + COLUMNS + PUBLISHED_JOIN + " WHERE " + VISIBLE + """
        <if test="kind != null"> AND c.kind=#{kind} </if>
        <if test="tag != null"> AND EXISTS(SELECT 1 FROM content_revision_tag t WHERE t.revision_id=r.id AND t.tag_id=#{tag}) </if>
        """ + KEYSET + " </script>")
    List<PublishedRow> listVisible(@Param("kind") ContentKind kind, @Param("tag") String tag,
            @Param("snapshot") LocalDateTime snapshot, @Param("lastAt") LocalDateTime lastAt,
            @Param("lastId") String lastId, @Param("take") int take);

    @Select("<script> SELECT " + COLUMNS + PUBLISHED_JOIN + " WHERE c.author_id=#{author} AND " + VISIBLE + KEYSET + " </script>")
    List<PublishedRow> listVisibleByAuthor(@Param("author") String author, @Param("snapshot") LocalDateTime snapshot,
            @Param("lastAt") LocalDateTime lastAt, @Param("lastId") String lastId, @Param("take") int take);

    @Select("""
        <script> SELECT ref.revision_id,ref.asset_id,ref.usage_type,ref.position,a.owner_id,a.scene,a.state
        FROM content_revision_asset ref LEFT JOIN media_asset a ON a.id=ref.asset_id WHERE ref.revision_id IN
        <foreach collection="revisions" item="id" open="(" separator="," close=")"> #{id} </foreach>
        ORDER BY ref.revision_id,ref.usage_type,ref.position </script>
        """)
    List<RevisionAssetRow> findAssetsForRevisions(@Param("revisions") List<String> revisions);

    @Select("""
        <script> SELECT ref.revision_id,t.id,t.name,t.description,t.status,t.version
        FROM content_revision_tag ref LEFT JOIN community_tag t ON t.id=ref.tag_id WHERE ref.revision_id IN
        <foreach collection="revisions" item="id" open="(" separator="," close=")"> #{id} </foreach>
        ORDER BY ref.revision_id,ref.position </script>
        """)
    List<RevisionTagRow> findTagsForRevisions(@Param("revisions") List<String> revisions);

    @Select("<script> SELECT c.author_id,COUNT(*) AS visible_count " + PUBLISHED_JOIN + " WHERE " + VISIBLE + """
        AND c.author_id IN <foreach collection="authors" item="id" open="(" separator="," close=")"> #{id} </foreach>
        GROUP BY c.author_id </script>
        """)
    List<AuthorCount> countVisibleByAuthors(@Param("authors") List<String> authors);
}
