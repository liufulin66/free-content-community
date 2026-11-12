package com.freecontent.community.user.mapper;
import com.freecontent.community.user.model.PublicUserProfile;
import org.apache.ibatis.annotations.*;
@Mapper
public interface PublicUserMapper {
    @Select("SELECT id,display_name,bio,created_at FROM app_user WHERE id=#{id}")
    PublicUserProfile findById(@Param("id")String id);
    @Select("""
        <script> SELECT id,display_name,bio,created_at FROM app_user WHERE id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")"> #{id} </foreach> </script>
        """)
    java.util.List<PublicUserProfile> findByIds(@Param("ids") java.util.List<String> ids);
}
