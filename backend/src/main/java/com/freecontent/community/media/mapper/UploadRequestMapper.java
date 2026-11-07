package com.freecontent.community.media.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.media.entity.UploadRequestRecord;
import org.apache.ibatis.annotations.*;
@Mapper public interface UploadRequestMapper extends BaseMapper<UploadRequestRecord> {
    @Select("SELECT * FROM media_upload_request WHERE owner_id=#{ownerId} AND key_hash=#{keyHash} FOR UPDATE")
    UploadRequestRecord lockByKey(@Param("ownerId") String ownerId, @Param("keyHash") String keyHash);

    @Update("UPDATE media_upload_request SET lease_token=#{token},lease_until=#{until} WHERE id=#{id}")
    int renew(@Param("id") String id, @Param("token") String token, @Param("until") java.time.LocalDateTime until);

    @Update("UPDATE media_upload_request SET state='SUCCEEDED',upload_id=#{uploadId},lease_token=NULL,lease_until=NULL WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token}")
    int complete(@Param("id") String id, @Param("token") String token, @Param("uploadId") String uploadId);

    @Delete("DELETE FROM media_upload_request WHERE id=#{id} AND state='PROCESSING' AND lease_token=#{token}")
    int release(@Param("id") String id, @Param("token") String token);
}
