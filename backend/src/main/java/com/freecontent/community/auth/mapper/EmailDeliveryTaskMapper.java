package com.freecontent.community.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.freecontent.community.auth.entity.EmailDeliveryTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 邮件任务数据访问，通过条件认领和租约令牌保护投递结果回写。
 */
@Mapper
public interface EmailDeliveryTaskMapper extends BaseMapper<EmailDeliveryTask> {

    @Update("""
            UPDATE email_delivery_task
            SET status = 'CANCELLED', code_ciphertext = NULL, encryption_iv = NULL,
                encryption_key_id = NULL, lease_token = NULL, lease_until = NULL,
                last_error_code = 'CHALLENGE_REPLACED', updated_at = #{now}
            WHERE challenge_id = #{challengeId} AND status IN ('PENDING', 'RETRY', 'SENDING')
            """)
    int cancelForChallenge(@Param("challengeId") String challengeId,
                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE email_delivery_task
            SET status = 'CANCELLED', code_ciphertext = NULL, encryption_iv = NULL,
                encryption_key_id = NULL, lease_token = NULL, lease_until = NULL,
                last_error_code = #{reason}, updated_at = #{now}
            WHERE challenge_id = #{challengeId} AND status IN ('PENDING', 'RETRY', 'SENDING')
            """)
    int cancelAfterVerification(@Param("challengeId") String challengeId,
                                @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Select("""
            SELECT id FROM email_delivery_task
            WHERE (status IN ('PENDING', 'RETRY') AND next_attempt_at <= #{now})
               OR (status = 'SENDING' AND lease_until <= #{now})
            ORDER BY next_attempt_at, id
            LIMIT #{limit}
            """)
    List<String> findDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE email_delivery_task
            SET status = 'SENDING', attempt_count = attempt_count + 1,
                lease_token = #{token}, lease_until = #{leaseUntil}, updated_at = #{now}
            WHERE id = #{id}
              AND ((status IN ('PENDING', 'RETRY') AND next_attempt_at <= #{now})
                OR (status = 'SENDING' AND lease_until <= #{now}))
            """)
    int claim(@Param("id") String id, @Param("token") String token,
              @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE email_delivery_task
            SET status = 'SENT', sent_at = #{now}, updated_at = #{now},
                code_ciphertext = NULL, encryption_iv = NULL, encryption_key_id = NULL,
                lease_token = NULL, lease_until = NULL, last_error_code = NULL
            WHERE id = #{id} AND status = 'SENDING' AND lease_token = #{token}
            """)
    int markSent(@Param("id") String id, @Param("token") String token,
                 @Param("now") LocalDateTime now);

    @Update("""
            UPDATE email_delivery_task
            SET status = #{status}, last_error_code = #{errorCode}, updated_at = #{now},
                code_ciphertext = NULL, encryption_iv = NULL, encryption_key_id = NULL,
                lease_token = NULL, lease_until = NULL
            WHERE id = #{id} AND status = 'SENDING' AND lease_token = #{token}
            """)
    int finish(@Param("id") String id, @Param("token") String token,
               @Param("status") EmailDeliveryTask.Status status,
               @Param("errorCode") String errorCode, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE email_delivery_task
            SET status = 'RETRY', last_error_code = 'SMTP_FAILED', updated_at = #{now},
                next_attempt_at = #{nextAttempt}, lease_token = NULL, lease_until = NULL
            WHERE id = #{id} AND status = 'SENDING' AND lease_token = #{token}
            """)
    int retry(@Param("id") String id, @Param("token") String token,
              @Param("now") LocalDateTime now, @Param("nextAttempt") LocalDateTime nextAttempt);
}
