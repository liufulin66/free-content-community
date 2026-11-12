package com.freecontent.community.content.mapper;

import com.freecontent.community.content.model.persistence.ContentRow;
import com.freecontent.community.content.model.persistence.RevisionRow;
import org.apache.ibatis.annotations.*;

/** 内容聚合与修订的公共行锁查询，必须处于调用方的业务事务内。 */
@Mapper
public interface ContentLockMapper {
    @Select("""
            SELECT id,author_id,kind,lifecycle,publication_status,version,working_revision_id,published_revision_id,
                   active_review_id,created_at,updated_at,deleted_at
            FROM community_content WHERE id=#{id} AND author_id=#{owner} FOR UPDATE
            """)
    ContentRow lockOwned(@Param("id") String id,@Param("owner") String owner);
    @Select("""
            SELECT id,content_id,revision_number,status,title,body_markdown,created_at,updated_at
            FROM content_revision WHERE id=#{id} AND content_id=#{contentId} FOR UPDATE
            """)
    RevisionRow lockRevision(@Param("id") String id,@Param("contentId") String contentId);
}
