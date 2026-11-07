package com.freecontent.community.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.media.entity.MediaProcessingTask;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MediaProcessingTaskMapper extends BaseMapper<MediaProcessingTask> {
    @Select("SELECT * FROM media_processing_task WHERE id=#{id} AND owner_id=#{owner} AND type='MEDIA_PROCESSING'")
    MediaProcessingTask findOwned(@Param("id") String id, @Param("owner") String owner);
}
