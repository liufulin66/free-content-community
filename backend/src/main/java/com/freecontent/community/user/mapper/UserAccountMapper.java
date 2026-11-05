package com.freecontent.community.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.user.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
    @Select("""
            SELECT id, email, email_normalized, password_hash, email_verified, email_verified_at,
                   display_name, bio, avatar_asset_id, account_status, version, auth_version, created_at, updated_at
            FROM app_user WHERE email_normalized = #{email}
            """)
    UserAccount findByEmail(@Param("email") String email);
}
