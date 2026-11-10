package com.freecontent.community.content.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.mapper.ContentMediaPreviewMapper;
import com.freecontent.community.content.model.ContentKind;
import com.freecontent.community.media.entity.MediaAsset;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.dto.MediaAccess;
import com.freecontent.community.media.storage.MediaObjectReader;
import com.freecontent.community.media.storage.MediaReadSigner;
import com.freecontent.community.content.config.ContentMediaPreviewProperties;
import com.freecontent.community.media.mapper.MediaAssetMapper;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.freecontent.community.user.model.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 两次短事务复核同一授权和读取绑定；HEAD在事务外，本地签名与最小审计原子完成。 */
@Service
public class ContentMediaPreviewTransactionService {
    private final ContentMediaPreviewMapper references;
    private final MediaAssetMapper assets;
    private final UserAccountMapper users;
    private final UserRoleMapper roles;
    private final StorageProperties storage;
    private final MediaReadSigner signer;
    private final ContentMediaPreviewProperties properties;
    private final Clock clock;
    public ContentMediaPreviewTransactionService(ContentMediaPreviewMapper references,MediaAssetMapper assets,UserAccountMapper users,UserRoleMapper roles,
            StorageProperties storage,MediaReadSigner signer,ContentMediaPreviewProperties properties,Clock clock) {
        this.references = references;
        this.assets = assets;
        this.users = users;
        this.roles = roles;
        this.storage = storage;
        this.signer = signer;
        this.properties = properties;
        this.clock = clock;
    }
    @Transactional(readOnly = true)
    public MediaObjectReader.ObjectReference prepare(String content,String revision,String assetId,SessionPrincipal principal) {
        return proof(content,revision,assetId,principal);
    }

    @Transactional
    public MediaAccess finish(String content,String revision,String asset,SessionPrincipal principal,MediaObjectReader.ObjectReference checked,String requestId) {
        var current = proof(content,revision,asset,principal);
        if (!current.equals(checked)) throw unavailable();
        Instant issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var access = signer.sign(current,issued,properties.readUrlTtlSeconds());
        if (references.insertAudit(UUID.randomUUID().toString().replace("-",""),principal.user().id(),content,revision,asset,requestId,
                LocalDateTime.ofInstant(issued,ZoneOffset.UTC),LocalDateTime.ofInstant(access.expiresAt(),ZoneOffset.UTC)) != 1) throw unavailable();
        return access;
    }

    private MediaObjectReader.ObjectReference proof(String content,String revision,String assetId,SessionPrincipal principal) {
        String owner = authorize(principal);
        var owned = references.findOwned(content,owner);
        if (owned == null || references.findRevision(content,revision) == null) throw missing();
        var usages = references.findUsages(revision,assetId); if (usages.isEmpty()) throw missing();
        var asset = assets.findOwnedForShare(assetId,owner); if (asset == null) throw missing();
        for (String usage : usages) {
            boolean image = (usage.equals("COVER") || usage.equals("IMAGE")) && asset.getScene().equals("CONTENT_IMAGE");
            boolean video = usage.equals("VIDEO") && owned.kind() == ContentKind.VIDEO && asset.getScene().equals("CONTENT_VIDEO");
            if (!image && !video) throw missing();
        }
        if (!"READY".equals(asset.getState())) throw new BusinessException(ErrorCode.MEDIA_NOT_READY);
        return readyObject(assetId,owner,asset);
    }

    private String authorize(SessionPrincipal principal) {
        var account = users.findByIdForShare(principal.user().id());
        if (account == null || account.getAuthVersion() == null || account.getAuthVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (account.getAccountStatus() == UserAccount.Status.DISABLED) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.getEmailVerified()) || !roles.findRolesForShare(account.getId()).contains(UserRole.USER)) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        return account.getId();
    }

    private MediaObjectReader.ObjectReference readyObject(String assetId,String owner,MediaAsset asset) {
        if (asset.getActualContentType() == null || asset.getActualSizeBytes() == null) throw unavailable();
        String prefix = asset.getScene().equals("CONTENT_IMAGE") ? "image/" : "video/";
        if (!asset.getActualContentType().toLowerCase(Locale.ROOT).startsWith(prefix)) throw unavailable();
        var result = new MediaObjectReader.ObjectReference(assetId,owner,asset.getReadyObjectKey(),asset.getReadyObjectEtag(),
                asset.getReadyStorageId(),asset.getActualContentType(),asset.getActualSizeBytes());
        result.requireStorage(storage);
        return result;
    }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"已校验媒体读取绑定暂不可用"); }
    private BusinessException missing() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
}
