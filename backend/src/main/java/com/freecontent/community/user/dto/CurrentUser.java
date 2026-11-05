package com.freecontent.community.user.dto;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.model.UserRole;

public record CurrentUser(
        String id,//用户ID
        String email,//用户邮箱
        boolean emailVerified,//邮箱是否已验证
        String displayName,//用户显示名称
        String bio,//用户简介
        String avatarAssetId,//用户头像资产ID
        List<UserRole> roles,//用户角色
        UserAccount.Status accountStatus,//账户状态
        boolean canCreate,//是否可以创建内容
        int version,//版本号
        Instant createdAt
) {
    public CurrentUser {
        roles = List.copyOf(roles);
    }

    public static CurrentUser from(UserAccount account, List<UserRole> roles) {
        return new CurrentUser(account.getId(), account.getEmail(), account.getEmailVerified(),
                account.getDisplayName(), account.getBio(), account.getAvatarAssetId(), roles,
                account.getAccountStatus(),
                account.getEmailVerified() && account.getAccountStatus() == UserAccount.Status.ACTIVE
                        && roles.contains(UserRole.USER),
                account.getVersion(), account.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
