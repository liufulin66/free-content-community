package com.freecontent.community.user.mapper;

import com.freecontent.community.user.model.UserRole;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserRoleMapper {
    @Insert("""
            INSERT INTO app_user_role (user_id, role_code, created_at)
            VALUES (#{userId}, 'USER', #{now})
            """)
    int insertDefaultRole(@Param("userId") String userId, @Param("now") LocalDateTime now);

    @Select("SELECT role_code FROM app_user_role WHERE user_id = #{userId} ORDER BY role_code")
    List<UserRole> findRoles(@Param("userId") String userId);

    @Select("SELECT role_code FROM app_user_role WHERE user_id=#{userId} ORDER BY role_code FOR SHARE")
    List<UserRole> findRolesForShare(@Param("userId") String userId);
}
