package com.freecontent.community.media.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.media.entity.MediaUpload;

@Mapper
public interface MediaUploadMapper extends BaseMapper<MediaUpload> {
    @Update("UPDATE media_upload_session SET state='COMPLETED',version=version+1 WHERE id=#{id} AND owner_id=#{owner} AND version=#{version} AND state='UPLOADING' AND expires_at>#{now}")
    int completeUpload(@Param("id") String id, @Param("owner") String owner, @Param("version") int version, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM media_upload_session WHERE id=#{id} AND owner_id=#{ownerId} FOR SHARE")
    MediaUpload findOwnedForShare(@Param("id") String id, @Param("ownerId") String ownerId);

    @Select("SELECT * FROM media_upload_session WHERE id=#{id} AND owner_id=#{ownerId} FOR UPDATE")
    MediaUpload findOwnedForUpdate(@Param("id") String id, @Param("ownerId") String ownerId);

    @Update("UPDATE media_upload_session SET state='CANCELLED',version=version+1 WHERE id=#{id} AND owner_id=#{ownerId} AND version=#{version} AND state='UPLOADING' AND expires_at>#{now}")
    int cancel(@Param("id") String id, @Param("ownerId") String ownerId, @Param("version") int version, @Param("now") LocalDateTime now);
}
