package com.freecontent.community.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.auth.entity.EmailChallenge;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 验证码申请数据访问，通过锁定查询和条件更新支持事务内替换。
 */
@Mapper
public interface EmailChallengeMapper extends BaseMapper<EmailChallenge> {

    @Select("""
            SELECT id, email_normalized, delivery_email, purpose, code_mac, mac_key_id,
                   status, failed_attempts, expires_at, resend_after, consumed_at,
                   invalidated_at, created_at, updated_at
            FROM email_challenge
            WHERE email_normalized = #{email} AND purpose = #{purpose} AND active_slot = 1
            FOR UPDATE
            """)
    EmailChallenge findActiveForUpdate(@Param("email") String email,
                                       @Param("purpose") EmailChallengePurpose purpose);

    @Update("""
            UPDATE email_challenge
            SET status = 'INVALIDATED', invalidated_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND status = 'ACTIVE'
            """)
    int invalidate(@Param("id") String id, @Param("now") LocalDateTime now);
}
