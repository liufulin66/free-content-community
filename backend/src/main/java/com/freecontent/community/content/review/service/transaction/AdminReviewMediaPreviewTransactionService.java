package com.freecontent.community.content.review.service.transaction;

import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.review.config.AdminReviewMediaPreviewProperties;
import com.freecontent.community.content.review.mapper.AdminReviewMediaPreviewMapper;
import com.freecontent.community.content.model.ContentKind;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.dto.MediaAccess;
import com.freecontent.community.media.mapper.MediaAssetMapper;
import com.freecontent.community.media.storage.*;
import com.freecontent.community.user.model.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 两次短事务锁定授权与冻结修订，HEAD在事务外；本地签名与审计共同完成。 */
@Service
public class AdminReviewMediaPreviewTransactionService {
    private static final Set<String> FROZEN = Set.of("IN_REVIEW", "APPROVED", "REJECTED", "CANCELLED");
    private final AdminReviewMediaPreviewMapper references;
    private final MediaAssetMapper assets;
    private final AccountAccessService accounts;
    private final StorageProperties storage;
    private final MediaReadSigner signer;
    private final AdminReviewMediaPreviewProperties properties;
    private final Clock clock;
    public AdminReviewMediaPreviewTransactionService(AdminReviewMediaPreviewMapper references, MediaAssetMapper assets, AccountAccessService accounts,
            StorageProperties storage, MediaReadSigner signer, AdminReviewMediaPreviewProperties properties, Clock clock) {
        this.references = references; this.assets = assets; this.accounts = accounts; this.storage = storage; this.signer = signer; this.properties = properties; this.clock = clock;
    }
    public record Proof(String reviewId, String contentId, String revisionId, String authorId, MediaObjectReader.ObjectReference object) { }

    @Transactional(readOnly = true)
    public Proof prepare(String reviewId, String assetId, SessionPrincipal principal) { return proof(reviewId, assetId, principal); }

    @Transactional
    public MediaAccess finish(String reviewId, String assetId, SessionPrincipal principal, Proof checked, String requestId) {
        var current = proof(reviewId, assetId, principal);
        if (!current.equals(checked)) throw unavailable();
        Instant issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var result = signer.sign(current.object(), issued, properties.readUrlTtlSeconds());
        if (references.insertAudit(UUID.randomUUID().toString().replace("-", ""), principal.user().id(), reviewId, current.contentId(),
                current.revisionId(), assetId, requestId, LocalDateTime.ofInstant(issued, ZoneOffset.UTC),
                LocalDateTime.ofInstant(result.expiresAt(), ZoneOffset.UTC)) != 1) throw unavailable();
        return result;
    }

    private Proof proof(String reviewId, String assetId, SessionPrincipal principal) {
        authorize(principal);
        String contentId = references.findContentId(reviewId); if (contentId == null) throw missing();
        // 与写入流程一致，内容锁先于审核锁；再次绑定contentId，不能跨内容拼接。
        var content = references.lockContent(contentId); if (content == null) throw unavailable();
        var review = references.lockReview(reviewId, contentId); if (review == null) throw missing();
        String status = references.lockRevisionStatus(contentId, review.revisionId());
        if (!FROZEN.contains(status == null ? "" : status)) throw unavailable();
        var usages = references.findUsages(review.revisionId(), assetId); if (usages.isEmpty()) throw missing();
        var asset = assets.findOwnedForShare(assetId, content.authorId()); if (asset == null) throw missing();
        for (String usage : usages) {
            boolean image = (usage.equals("COVER") || usage.equals("IMAGE")) && asset.getScene().equals("CONTENT_IMAGE");
            boolean video = usage.equals("VIDEO") && content.kind() == ContentKind.VIDEO && asset.getScene().equals("CONTENT_VIDEO");
            if (!image && !video) throw missing();
        }
        if (!"READY".equals(asset.getState())) throw new BusinessException(ErrorCode.MEDIA_NOT_READY);
        return new Proof(reviewId, contentId, review.revisionId(), content.authorId(), MediaObjectReader.ObjectReference.fromAsset(assetId, content.authorId(), asset, storage));
    }
    private void authorize(SessionPrincipal principal) {
        var account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (account.disabled()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.emailVerified()) || accounts.findRolesForShare(account.id()).stream().noneMatch(role -> role == UserRole.MODERATOR || role == UserRole.ADMIN))
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要审核员或管理员的review:read权限");
    }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "审核媒体读取绑定暂不可用"); }
    private BusinessException missing() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
}
