package com.freecontent.community.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.media.entity.UploadCleanupTask;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UploadCleanupTaskMapper extends BaseMapper<UploadCleanupTask> {
    @Select("SELECT id FROM media_upload_cleanup WHERE (state='PENDING' AND available_at<=#{now}) OR (state='RUNNING' AND lease_until<=#{now}) ORDER BY available_at,id LIMIT #{limit}")
    List<String> findDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("UPDATE media_upload_cleanup SET state='RUNNING',attempt_count=attempt_count+1,lease_token=#{token},lease_until=#{leaseUntil},updated_at=#{now} WHERE id=#{id} AND ((state='PENDING' AND available_at<=#{now}) OR (state='RUNNING' AND lease_until<=#{now}))")
    int claim(@Param("id") String id, @Param("token") String token, @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("UPDATE media_upload_cleanup SET state=#{state},last_error_code=#{errorCode,jdbcType=VARCHAR},lease_token=NULL,lease_until=NULL,updated_at=#{now} WHERE id=#{id} AND state='RUNNING' AND lease_token=#{token} AND lease_until>#{now}")
    int finish(@Param("id") String id, @Param("token") String token, @Param("state") String state, @Param("errorCode") String errorCode, @Param("now") LocalDateTime now);

    @Update("UPDATE media_upload_cleanup SET state='PENDING',available_at=#{retryAt},last_error_code='STORAGE_DELETE_FAILED',lease_token=NULL,lease_until=NULL,updated_at=#{now} WHERE id=#{id} AND state='RUNNING' AND lease_token=#{token} AND lease_until>#{now}")
    int retry(@Param("id") String id, @Param("token") String token, @Param("now") LocalDateTime now, @Param("retryAt") LocalDateTime retryAt);
}
