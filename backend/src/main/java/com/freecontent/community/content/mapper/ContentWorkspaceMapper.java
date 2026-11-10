package com.freecontent.community.content.mapper;

import com.freecontent.community.content.model.ContentKind;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ContentWorkspaceMapper {
    record ContentRow(String id, String authorId, ContentKind kind, String lifecycle, String publicationStatus, int version,
            String workingRevisionId, String publishedRevisionId, String activeReviewId,
            LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deletedAt) { }
    record RevisionRow(String id, String contentId, int revisionNumber, String status, String title, String bodyMarkdown,
            LocalDateTime createdAt, LocalDateTime updatedAt) { }
    record AssetReference(String assetId, String usageType, int position) { }
    record RevisionAssetReference(String revisionId,String assetId,String usageType,int position) { }
    record RevisionTagReference(String revisionId,String tagId,int position) { }

    String OWNED_LIST_FILTER = """
            FROM community_content WHERE author_id=#{owner} AND lifecycle=#{lifecycle}
            <if test="publicationStatus != null"> AND publication_status=#{publicationStatus} </if>
            <if test="kind != null"> AND kind=#{kind} </if>
            """;

    @Select("<script> SELECT COUNT(*) "+OWNED_LIST_FILTER+" </script>")
    long countOwned(@Param("owner") String owner,@Param("lifecycle") String lifecycle,@Param("publicationStatus") String publicationStatus,@Param("kind") String kind);

    @Select("""
            <script> SELECT id,author_id,kind,lifecycle,publication_status,version,working_revision_id,published_revision_id,
                    active_review_id,created_at,updated_at,deleted_at
            """+OWNED_LIST_FILTER+" ORDER BY updated_at DESC,id DESC LIMIT #{limit} OFFSET #{offset} </script>")
    List<ContentRow> pageOwned(@Param("owner") String owner,@Param("lifecycle") String lifecycle,@Param("publicationStatus") String publicationStatus,
            @Param("kind") String kind,@Param("offset") long offset,@Param("limit") int limit);

    @Select("""
            <script> SELECT id,content_id,revision_number,status,title,body_markdown,created_at,updated_at
            FROM content_revision WHERE id IN
            <foreach collection="revisionIds" item="id" open="(" separator="," close=")"> #{id} </foreach>
            AND content_id IN <foreach collection="contentIds" item="id" open="(" separator="," close=")"> #{id} </foreach>
            </script>
            """)
    List<RevisionRow> findRevisions(@Param("contentIds") List<String> contentIds,@Param("revisionIds") List<String> revisionIds);

    @Select("""
            <script> SELECT revision_id,asset_id,usage_type,position FROM content_revision_asset WHERE revision_id IN
            <foreach collection="revisionIds" item="id" open="(" separator="," close=")"> #{id} </foreach>
            ORDER BY revision_id,usage_type,position </script>
            """)
    List<RevisionAssetReference> findAssetsForRevisions(@Param("revisionIds") List<String> revisionIds);

    @Select("""
            <script> SELECT revision_id,tag_id,position FROM content_revision_tag WHERE revision_id IN
            <foreach collection="revisionIds" item="id" open="(" separator="," close=")"> #{id} </foreach>
            ORDER BY revision_id,position </script>
            """)
    List<RevisionTagReference> findTagsForRevisions(@Param("revisionIds") List<String> revisionIds);

    @Select("""
            SELECT id,author_id,kind,lifecycle,publication_status,version,working_revision_id,published_revision_id,
                   active_review_id,created_at,updated_at,deleted_at
            FROM community_content WHERE id=#{id} AND author_id=#{owner}
            """)
    ContentRow findOwned(@Param("id") String id, @Param("owner") String owner);

    @Select("""
            SELECT id,content_id,revision_number,status,title,body_markdown,created_at,updated_at
            FROM content_revision WHERE id=#{id} AND content_id=#{contentId}
            """)
    RevisionRow findRevision(@Param("id") String id, @Param("contentId") String contentId);

    @Select("SELECT asset_id,usage_type,position FROM content_revision_asset WHERE revision_id=#{revisionId} ORDER BY usage_type,position")
    List<AssetReference> findAssets(@Param("revisionId") String revisionId);

    @Select("SELECT tag_id FROM content_revision_tag WHERE revision_id=#{revisionId} ORDER BY position")
    List<String> findTagIds(@Param("revisionId") String revisionId);
}
