package com.freecontent.community.content.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.content.review.entity.ContentReviewSubmissionRequestRecord;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface ContentReviewSubmissionRequestMapper extends BaseMapper<ContentReviewSubmissionRequestRecord> {
    @Select("SELECT * FROM content_review_submission_request WHERE created_by=#{actor} AND key_hash=#{keyHash} FOR UPDATE")
    ContentReviewSubmissionRequestRecord lockByKey(@Param("actor") String actor, @Param("keyHash") String keyHash);
    @Update("UPDATE content_review_submission_request SET lease_token=#{token},lease_until=#{until} WHERE id=#{id} AND state='PROCESSING'")
    int renew(@Param("id") String id, @Param("token") String token, @Param("until") LocalDateTime until);
    @Update("""
            UPDATE content_review_submission_request SET state='SUCCEEDED',review_id=#{review},result_snapshot=#{snapshot},lease_token=NULL,lease_until=NULL
            WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token} AND lease_until>#{now} AND expires_at>#{now}
            """)
    int complete(@Param("id") String id, @Param("token") String token, @Param("review") String review,
            @Param("snapshot") String snapshot, @Param("now") LocalDateTime now);
    @Delete("DELETE FROM content_review_submission_request WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token}")
    int release(@Param("id") String id, @Param("token") String token);
}
