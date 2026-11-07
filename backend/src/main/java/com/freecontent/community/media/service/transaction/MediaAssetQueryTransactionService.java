package com.freecontent.community.media.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.dto.MediaAssetView;
import com.freecontent.community.media.mapper.MediaAssetMapper;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaAssetQueryTransactionService {
    private final UserAccountMapper users;
    private final MediaAssetMapper assets;
    public MediaAssetQueryTransactionService(UserAccountMapper users, MediaAssetMapper assets) {
        this.users = users; this.assets = assets;
    }

    @Transactional
    public MediaAssetView read(String assetId, SessionPrincipal principal) {
        UserAccount account = users.findByIdForShare(principal.user().id());
        if (account == null || account.getAuthVersion() == null || account.getAuthVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.getAccountStatus() == UserAccount.Status.DISABLED) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.getEmailVerified())) throw new BusinessException(ErrorCode.PERMISSION_DENIED, "当前账号不具备素材查询权限");
        var asset = assets.findOwned(assetId, account.getId());
        if (asset == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if ("READY".equals(asset.getState()) && (asset.getActualContentType() == null || asset.getActualContentType().isBlank()
                || asset.getActualSizeBytes() == null)) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "素材校验元数据暂不可用");
        }
        return MediaAssetView.from(asset);
    }
}
