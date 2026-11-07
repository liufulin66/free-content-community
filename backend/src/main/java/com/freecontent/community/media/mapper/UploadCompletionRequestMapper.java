package com.freecontent.community.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.media.entity.UploadCompletionRequestRecord;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface UploadCompletionRequestMapper extends BaseMapper<UploadCompletionRequestRecord> {
    @Select("SELECT * FROM media_upload_completion_request WHERE owner_id=#{owner} AND key_hash=#{keyHash} FOR UPDATE")
    UploadCompletionRequestRecord lockByKey(@Param("owner") String owner, @Param("keyHash") String keyHash);

    @Update("UPDATE media_upload_completion_request SET lease_token=#{token},lease_until=#{until} WHERE id=#{id} AND state='PROCESSING'")
    int renew(@Param("id") String id, @Param("token") String token, @Param("until") LocalDateTime until);

    @Update("UPDATE media_upload_completion_request SET state='SUCCEEDED',task_id=#{taskId},lease_token=NULL,lease_until=NULL WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token}")
    int complete(@Param("id") String id, @Param("token") String token, @Param("taskId") String taskId);

    @Delete("DELETE FROM media_upload_completion_request WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token}")
    int release(@Param("id") String id, @Param("token") String token);
}
