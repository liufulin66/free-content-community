package com.freecontent.community.user.service.transaction;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;

/** 仅在旧密码已于事务外验证后调用，写入前再次核对凭证快照。 */
@Service
public class PasswordChangeTransactionService {
    private final UserAccountMapper users;
    private final Clock clock;

    public PasswordChangeTransactionService(UserAccountMapper users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public long change(String userId, long expectedAuthVersion, String expectedPasswordHash, String newPasswordHash) {
        // 在事务中查找用户并加锁
        UserAccount current = users.findByIdForUpdate(userId);
        // 核对凭证快照，确保事务外验证的旧密码仍然有效
        if (current == null || current.getAuthVersion() == null || current.getAuthVersion() != expectedAuthVersion
                || !Objects.equals(current.getPasswordHash(), expectedPasswordHash)) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "认证状态已改变，请重新登录");
        }
        // 检查账号状态
        if (current.getAccountStatus() == UserAccount.Status.DISABLED) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED, "账号已被禁用");
        }
        // 更新密码哈希和凭证版本号
        if (users.updatePasswordHash(userId, newPasswordHash, expectedAuthVersion,
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)) != 1) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "认证状态已改变，请重新登录");
        }
        return Math.addExact(expectedAuthVersion, 1L);
    }
}
