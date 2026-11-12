package com.freecontent.community.content.browse.service.transaction;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.browse.config.PublishedMediaAccessProperties;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.browse.mapper.PublishedMediaAccessMapper;
import com.freecontent.community.content.browse.service.read.PublicContentAssembler;
import com.freecontent.community.content.model.ContentKind;
import com.freecontent.community.media.config.StorageProperties;
import com.freecontent.community.media.dto.MediaAccess;
import com.freecontent.community.media.storage.MediaObjectReader;
import com.freecontent.community.media.storage.MediaReadSigner;
import java.time.Clock;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 授权短事务 → 事务外 HEAD → 最终当前读短事务；本地签名完成后释放授权依据的共享锁。 */
@Service
public class PublishedMediaAccessTransactionService {
    private final PublishedMediaAccessMapper references;
    private final PublicContentAssembler assembler;
    private final StorageProperties storage;
    private final MediaReadSigner signer;
    private final Clock clock;
    private final PublishedMediaAccessProperties properties;

    public PublishedMediaAccessTransactionService(PublishedMediaAccessMapper references, PublicContentAssembler assembler,
            StorageProperties storage, MediaReadSigner signer, Clock clock, PublishedMediaAccessProperties properties) {
        this.references = references;
        this.assembler = assembler;
        this.storage = storage;
        this.signer = signer;
        this.clock = clock;
        this.properties = properties;
    }

    public record Proof(String contentId, String revisionId, MediaObjectReader.ObjectReference object) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Proof prepare(String contentId, String assetId) {
        return proof(contentId, assetId);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public MediaAccess finish(String contentId, String assetId, Proof checked) {
        var current = proof(contentId, assetId);
        if (!current.equals(checked)) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "公开媒体读取绑定已变化，请重新申请");
        return signer.sign(current.object(), clock.instant(), properties.readUrlTtlSeconds());
    }

    private Proof proof(String contentId, String assetId) {
        var content = lockPublishedRevision(contentId);
        var bindings = references.lockReferences(content.revisionId());
        var usages = bindings.stream().filter(value -> assetId.equals(value.assetId())).toList();
        if (usages.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var assetIds = bindings.stream().map(value -> value.assetId()).distinct().sorted().toList();
        var assets = references.lockAssets(content.authorId(), assetIds).stream().collect(Collectors.toMap(value -> value.getId(), Function.identity()));
        var asset = assets.get(assetId);
        if (asset == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        for (var usage : usages) {
            boolean image = (usage.usageType().equals("COVER") || usage.usageType().equals("IMAGE")) && asset.getScene().equals("CONTENT_IMAGE");
            boolean video = usage.usageType().equals("VIDEO") && content.kind() == ContentKind.VIDEO && asset.getScene().equals("CONTENT_VIDEO");
            if (!image && !video) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        // 先验证目标的归属和用途，再区分其未READY的409与其他关联素材使整篇不可见的404。
        if (!"READY".equals(asset.getState())) throw new BusinessException(ErrorCode.MEDIA_NOT_READY);
        var rows = bindings.stream().map(binding -> {
            var value = assets.get(binding.assetId());
            return new PublicContentMapper.AssetRow(binding.assetId(), binding.usageType(), binding.position(),
                    value == null ? null : value.getOwnerId(), value == null ? null : value.getScene(), value == null ? null : value.getState());
        }).toList();
        assembler.media(content, rows);
        assembler.tags(references.lockTags(content.revisionId()));
        return new Proof(contentId, content.revisionId(), MediaObjectReader.ObjectReference.fromAsset(assetId, content.authorId(), asset, storage));
    }

    private PublicContentMapper.PublishedRow lockPublishedRevision(String contentId) {
        var publication = references.lockPublication(contentId);
        if (publication == null || !"ACTIVE".equals(publication.lifecycle()) || !"PUBLISHED".equals(publication.publicationStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        var revision = publication.publishedRevisionId() == null ? null : references.lockRevision(contentId, publication.publishedRevisionId());
        var content = new PublicContentMapper.PublishedRow(publication.id(), publication.authorId(), publication.kind(), publication.lifecycle(),
                publication.publicationStatus(), publication.publishedRevisionId(), publication.firstPublishedAt(), publication.publishedAt(),
                revision == null ? null : revision.id(), revision == null ? null : revision.status(),
                revision == null ? null : revision.title(), revision == null ? null : revision.bodyMarkdown());
        assembler.requirePublicRevision(content);
        return content;
    }
}
