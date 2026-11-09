package com.freecontent.community.tag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.tag.entity.TagCreationRequestRecord;
import com.freecontent.community.tag.entity.TagEntity;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface TagCreationRequestMapper extends BaseMapper<TagCreationRequestRecord> {
    @Select("SELECT * FROM tag_creation_request WHERE created_by=#{actor} AND key_hash=#{keyHash} FOR UPDATE")
    TagCreationRequestRecord lockByKey(@Param("actor") String actor, @Param("keyHash") String keyHash);
    @Update("UPDATE tag_creation_request SET lease_token=#{token},lease_until=#{until} WHERE id=#{id} AND state='PROCESSING'")
    int renew(@Param("id") String id, @Param("token") String token, @Param("until") LocalDateTime until);
    @Update("""
            UPDATE tag_creation_request SET state='SUCCEEDED',tag_id=#{tag.id},result_name=#{tag.name},
                result_description=#{tag.description},result_status=#{tag.status},result_version=#{tag.version},
                lease_token=NULL,lease_until=NULL
            WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token} AND lease_until>#{now}
            """)
    int complete(@Param("id") String id, @Param("token") String token, @Param("tag") TagEntity tag, @Param("now") LocalDateTime now);
    @Delete("DELETE FROM tag_creation_request WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token}")
    int release(@Param("id") String id, @Param("token") String token);
}
