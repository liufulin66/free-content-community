package com.freecontent.community.user.service.transaction;

import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.*;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.Objects;

@Service
public class ProfileUpdateTransactionService {
    private final UserAccountMapper users;
    private final UserRoleMapper roles;
    private final Clock clock;

    public ProfileUpdateTransactionService(UserAccountMapper users, UserRoleMapper roles, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.clock = clock;
    }

    @Transactional
    public CurrentUser update(UpdateProfileRequest patch, String userId, long expectedAuthVersion) {
        UserAccount current = users.findByIdForUpdate(userId);
        if (current == null || current.getAuthVersion() == null || current.getAuthVersion() != expectedAuthVersion) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "认证状态已改变，请重新登录");
        }
        if (current.getAccountStatus() != UserAccount.Status.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED, "账号暂不能修改资料");
        }
        if (!Objects.equals(patch.getExpectedVersion(), current.getVersion())) throw conflict(current.getVersion());
        if (patch.hasAvatarAssetId() && patch.getAvatarAssetId() != null) {
            // 素材服务尚未接入；不绕过本人、AVATAR用途及READY状态的校验。
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "头像素材服务暂不可用");
        }
        boolean changed = (patch.hasDisplayName() && !Objects.equals(patch.getDisplayName(), current.getDisplayName()))
                || (patch.hasBio() && !Objects.equals(patch.getBio(), current.getBio()))
                || (patch.hasAvatarAssetId() && !Objects.equals(patch.getAvatarAssetId(), current.getAvatarAssetId()));
        if (!changed) return view(current);
        if (users.updateProfileFields(userId, patch.getExpectedVersion(), expectedAuthVersion, patch,
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)) != 1) throw conflict(current.getVersion());
        return view(users.selectById(userId));
    }

    private CurrentUser view(UserAccount account) { return CurrentUser.from(account, roles.findRoles(account.getId())); }

    private BusinessException conflict(int version) {
        return new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "资料已被修改，请重新读取后再保存", version);
    }
}
