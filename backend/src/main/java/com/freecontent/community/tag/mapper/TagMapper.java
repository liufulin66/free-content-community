package com.freecontent.community.tag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.tag.entity.TagEntity;
import com.freecontent.community.tag.dto.TagUpdateRequest;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface TagMapper extends BaseMapper<TagEntity> {
    @Select("SELECT * FROM community_tag WHERE id=#{tagId} FOR UPDATE")
    TagEntity findByIdForUpdate(@Param("tagId") String tagId);

    @Update("""
            <script>
            UPDATE community_tag
            <set>
              <if test="patch.hasName()">name=#{patch.name},</if>
              <if test="patch.hasDescription()">description=#{patch.description},</if>
              <if test="patch.hasStatus()">status=#{patch.status},</if>
              version=version+1, updated_at=#{now}
            </set>
            WHERE id=#{tagId} AND version=#{expectedVersion}
            </script>
            """)
    int updateFields(@Param("tagId") String tagId, @Param("expectedVersion") int expectedVersion,
                     @Param("patch") TagUpdateRequest patch, @Param("now") LocalDateTime now);
}
