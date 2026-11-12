package com.freecontent.community.content.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.content.review.entity.ReviewDecisionRequestRecord;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface ReviewDecisionRequestMapper extends BaseMapper<ReviewDecisionRequestRecord> {
    @Select("SELECT * FROM content_review_decision_request WHERE created_by=#{actor} AND key_hash=#{keyHash} FOR UPDATE")
    ReviewDecisionRequestRecord lockByKey(@Param("actor") String actor, @Param("keyHash") String keyHash);
    @Update("UPDATE content_review_decision_request SET lease_token=#{token},lease_until=#{until} WHERE id=#{id} AND state='PROCESSING'")
    int renew(@Param("id") String id, @Param("token") String token, @Param("until") LocalDateTime until);
    @Update("""
            UPDATE content_review_decision_request SET state='SUCCEEDED',content_id=#{content},result_snapshot=#{snapshot},lease_token=NULL,lease_until=NULL
            WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token} AND lease_until>#{now} AND expires_at>#{now}
            """)
    int complete(@Param("id") String id, @Param("token") String token, @Param("content") String content,
            @Param("snapshot") String snapshot, @Param("now") LocalDateTime now);
    @Delete("DELETE FROM content_review_decision_request WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token}")
    int release(@Param("id") String id, @Param("token") String token);
}
