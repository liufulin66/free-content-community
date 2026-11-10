package com.freecontent.community.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.content.entity.ContentCreationRequestRecord;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface ContentCreationRequestMapper extends BaseMapper<ContentCreationRequestRecord> {
    @Select("SELECT * FROM content_creation_request WHERE created_by=#{actor} AND key_hash=#{keyHash} FOR UPDATE")
    ContentCreationRequestRecord lockByKey(@Param("actor") String actor, @Param("keyHash") String keyHash);

    @Update("UPDATE content_creation_request SET lease_token=#{token},lease_until=#{until} WHERE id=#{id} AND state='PROCESSING'")
    int renew(@Param("id") String id, @Param("token") String token, @Param("until") LocalDateTime until);

    @Update("""
            UPDATE content_creation_request SET state='SUCCEEDED',content_id=#{contentId},result_snapshot=#{snapshot},lease_token=NULL,lease_until=NULL
            WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token} AND lease_until>#{now} AND expires_at>#{now}
            """)
    int complete(@Param("id") String id, @Param("token") String token, @Param("contentId") String contentId,
            @Param("snapshot") String snapshot, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM content_creation_request WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token}")
    int release(@Param("id") String id, @Param("token") String token);
}
