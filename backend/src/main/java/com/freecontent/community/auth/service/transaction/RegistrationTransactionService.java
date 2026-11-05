package com.freecontent.community.auth.service.transaction;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.freecontent.community.auth.config.RegistrationProperties;
import com.freecontent.community.auth.dto.RegisterRequest;
import com.freecontent.community.auth.mapper.EmailChallengeMapper;
import com.freecontent.community.auth.mapper.EmailDeliveryTaskMapper;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.user.dto.CurrentUser;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.freecontent.community.user.model.UserRole;

/** 注册写事务：复核挑战，并原子创建账号、授予角色、消费验证码。 */
@Service
public class RegistrationTransactionService {
    private final RegistrationVerificationService verification;
    private final EmailChallengeMapper challenges;
    private final EmailDeliveryTaskMapper tasks;
    private final UserAccountMapper users;
    private final UserRoleMapper roles;
    private final RegistrationProperties properties;
    private final Clock clock;

    public RegistrationTransactionService(RegistrationVerificationService verification, EmailChallengeMapper challenges,
                              EmailDeliveryTaskMapper tasks, UserAccountMapper users,
                              UserRoleMapper roles, RegistrationProperties properties, Clock clock) {
        this.verification = verification;
        this.challenges = challenges;
        this.tasks = tasks;
        this.users = users;
        this.roles = roles;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Result register(RegisterRequest request, String email, String passwordHash) {
        // 验证码预检查，错码以false返回，让失败计数提交后，再由事务外的Service抛出400。
        if (!verification.verify(request, email)) {
            return new Result(Status.INVALID_CHALLENGE, null);
        }
        // 检查邮箱是否已被注册，如果已注册则返回EMAIL_UNAVAILABLE状态
        if (users.findByEmail(email) != null) {
            return new Result(Status.EMAIL_UNAVAILABLE, null);
        }
        LocalDateTime now = now();
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID().toString().replace("-", ""));
        user.setEmail(request.email());
        user.setEmailNormalized(email);
        user.setPasswordHash(passwordHash);
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(now);
        user.setDisplayName(request.displayName());
        user.setBio("");
        user.setAccountStatus(UserAccount.Status.ACTIVE);
        user.setVersion(1);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        // 创建用户账号和授予默认角色，如果操作失败则抛出异常，触发事务回滚
        if (users.insert(user) != 1 || roles.insertDefaultRole(user.getId(), now) != 1) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "账号创建暂未完成");
        }
        // 消费验证码，确保验证码在注册成功后被标记为已使用，防止重复使用
        LocalDateTime consumedAt = now();
        if (challenges.consumeRegistration(request.challengeId(), properties.maxCodeFailures(), consumedAt) != 1) {
            // 抛出运行时异常使新账号、角色和消费操作全部回滚。
            throw new BusinessException(ErrorCode.CHALLENGE_INVALID, "验证码无效或已失效");
        }
        // 取消后续投递任务，确保验证码在注册成功后不再被发送
        tasks.cancelAfterVerification(request.challengeId(), "CHALLENGE_CONSUMED", consumedAt);
        List<UserRole> assignedRoles = roles.findRoles(user.getId());
        if (!assignedRoles.equals(List.of(UserRole.USER))) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "账号角色初始化失败");
        }
        return new Result(Status.REGISTERED, CurrentUser.from(user, assignedRoles));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public enum Status {
        REGISTERED, INVALID_CHALLENGE, EMAIL_UNAVAILABLE
    }

    public record Result(Status status, CurrentUser user) {
    }
}
