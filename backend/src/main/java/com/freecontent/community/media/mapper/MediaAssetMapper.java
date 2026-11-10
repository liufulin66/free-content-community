package com.freecontent.community.media.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.media.entity.MediaAsset;

@Mapper
public interface MediaAssetMapper extends BaseMapper<MediaAsset> {
    @Select("SELECT * FROM media_asset WHERE id=#{id} AND owner_id=#{owner}")
    MediaAsset findOwned(@Param("id") String id, @Param("owner") String owner);

    @Select("SELECT * FROM media_asset WHERE id=#{id} AND owner_id=#{owner} FOR SHARE")
    MediaAsset findOwnedForShare(@Param("id") String id,@Param("owner") String owner);

    @Update("UPDATE media_asset SET state='PROCESSING' WHERE id=#{id} AND owner_id=#{owner} AND state='WAITING_UPLOAD'")
    int startProcessing(@Param("id") String id, @Param("owner") String owner);

    @Select("SELECT * FROM media_asset WHERE id=#{id} AND owner_id=#{ownerId} FOR UPDATE")
    MediaAsset findOwnedForUpdate(@Param("id") String id, @Param("ownerId") String ownerId);

    @Update("UPDATE media_asset SET state='FAILED' WHERE id=#{id} AND owner_id=#{ownerId} AND state='WAITING_UPLOAD'")
    int failCancelledUpload(@Param("id") String id, @Param("ownerId") String ownerId);
}
