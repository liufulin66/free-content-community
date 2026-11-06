package com.freecontent.community.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.user.entity.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import com.freecontent.community.user.dto.UpdateProfileRequest;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
    @Select("""
            SELECT id, email, email_normalized, password_hash, email_verified, email_verified_at,
                   display_name, bio, avatar_asset_id, account_status, version, auth_version, created_at, updated_at
            FROM app_user WHERE email_normalized = #{email}
            """)
    UserAccount findByEmail(@Param("email") String email);

    @Select("""
            SELECT id, email, email_normalized, password_hash, email_verified, email_verified_at,
                   display_name, bio, avatar_asset_id, account_status, version, auth_version, created_at, updated_at
            FROM app_user WHERE email_normalized = #{email} FOR UPDATE
            """)
    UserAccount findByEmailForUpdate(@Param("email") String email);

    @Update("""
            UPDATE app_user SET password_hash=#{passwordHash}, auth_version=auth_version+1, updated_at=#{now}
            WHERE id=#{id} AND auth_version=#{expectedAuthVersion}
            """)
    int updatePasswordHash(@Param("id") String id, @Param("passwordHash") String passwordHash,
                               @Param("expectedAuthVersion") long expectedAuthVersion,
                               @Param("now") LocalDateTime now);

    @Select("""
            SELECT id, email, email_normalized, password_hash, email_verified, email_verified_at,
                   display_name, bio, avatar_asset_id, account_status, version, auth_version, created_at, updated_at
            FROM app_user WHERE id = #{id} FOR UPDATE
            """)
    UserAccount findByIdForUpdate(@Param("id") String id);

    @Update("""
            <script>
            UPDATE app_user
            <set>
              <if test="patch.hasDisplayName()">display_name=#{patch.displayName,jdbcType=VARCHAR},</if>
              <if test="patch.hasBio()">bio=#{patch.bio,jdbcType=VARCHAR},</if>
              <if test="patch.hasAvatarAssetId()">avatar_asset_id=#{patch.avatarAssetId,jdbcType=VARCHAR},</if>
              version=version+1, updated_at=#{now}
            </set>
            WHERE id=#{id} AND version=#{expectedVersion}
              AND auth_version=#{expectedAuthVersion} AND account_status='ACTIVE'
            </script>
            """)
    int updateProfileFields(@Param("id") String id, @Param("expectedVersion") int expectedVersion,
                            @Param("expectedAuthVersion") long expectedAuthVersion,
                            @Param("patch") UpdateProfileRequest patch, @Param("now") LocalDateTime now);
}
