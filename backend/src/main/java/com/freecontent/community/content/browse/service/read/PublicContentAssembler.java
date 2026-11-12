package com.freecontent.community.content.browse.service.read;

import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.dto.PublicContentView;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.browse.mapper.PublicContentMapper.PublishedRow;
import com.freecontent.community.content.browse.service.ContentSummary;
import com.freecontent.community.content.model.ContentKind;
import com.freecontent.community.tag.dto.TagView;
import com.freecontent.community.user.dto.PublicUserView;
import com.freecontent.community.user.model.PublicUserProfile;
import org.springframework.stereotype.Component;
import java.time.ZoneOffset;
import java.util.*;

/** 创作组/审核组之外的纯发布投影；详情和批量列表共用相同校验与字段生成规则。 */
@Component
public class PublicContentAssembler {
    private final ContentSummary summary;
    public PublicContentAssembler(ContentSummary summary) { this.summary = summary; }

    public void requirePublicRevision(PublishedRow row) {
        if (row == null || !"ACTIVE".equals(row.lifecycle()) || !"PUBLISHED".equals(row.publicationStatus())) throw missing();
        if (row.publishedRevisionId() == null || row.revisionId() == null
                || !row.publishedRevisionId().equals(row.revisionId()) || row.firstPublishedAt() == null
                || row.publishedAt() == null || row.firstPublishedAt().isAfter(row.publishedAt())) throw unavailable();
        if (!"APPROVED".equals(row.revisionStatus())) throw missing();
        if (row.title() == null || row.title().isBlank() || row.bodyMarkdown() == null || row.kind() == null) throw unavailable();
    }

    public record MediaReferences(String cover, List<String> images, String video) { }

    public MediaReferences media(PublishedRow row, List<PublicContentMapper.AssetRow> assets) {
        List<String> images = new ArrayList<>();
        String cover = null, video = null;
        for (var asset : assets) {
            boolean imagePurpose = Set.of("COVER", "IMAGE").contains(asset.usageType());
            boolean videoPurpose = "VIDEO".equals(asset.usageType()) && row.kind() == ContentKind.VIDEO;
            if (!row.authorId().equals(asset.ownerId()) || !"READY".equals(asset.state())
                    || !(imagePurpose && "CONTENT_IMAGE".equals(asset.scene())
                    || videoPurpose && "CONTENT_VIDEO".equals(asset.scene()))) throw missing();
            switch (asset.usageType()) {
                case "COVER" -> { if (cover != null) throw unavailable(); cover = asset.assetId(); }
                case "VIDEO" -> { if (video != null) throw unavailable(); video = asset.assetId(); }
                case "IMAGE" -> images.add(asset.assetId());
                default -> throw unavailable();
            }
        }
        if (row.kind() == ContentKind.VIDEO && video == null) throw missing();
        if (images.size() > 50 || new HashSet<>(images).size() != images.size()) throw unavailable();
        return new MediaReferences(cover, images, video);
    }

    public List<TagView> tags(List<PublicContentMapper.TagRow> tags) {
        if (tags.size() > 5) throw unavailable();
        for (var tag : tags) {
            if (tag.id() == null || tag.name() == null || tag.description() == null || tag.status() == null || tag.version() < 1) throw unavailable();
        }
        return tags.stream().map(tag -> new TagView(tag.id(), tag.name(), tag.description(), tag.status(), tag.version())).toList();
    }

    public void requireAuthor(PublicUserProfile author) {
        if (author == null || author.createdAt() == null || author.displayName() == null || author.bio() == null) throw unavailable();
    }

    public PublicContentView content(PublishedRow row, MediaReferences media, List<TagView> tags, PublicUserProfile author, long count) {
        requireAuthor(author);
        var profile = new PublicUserView(author.id(), author.displayName(), author.bio(), null, author.createdAt().toInstant(ZoneOffset.UTC), count, 0);
        // 保留已确认的互动/头像边界；正文只用于详情及服务端摘要，不进入列表卡片。
        return new PublicContentView(row.id(), row.kind(), row.title(), summary.summarize(row.bodyMarkdown()), profile,
                media.cover(), tags, row.firstPublishedAt().toInstant(ZoneOffset.UTC), row.publishedAt().toInstant(ZoneOffset.UTC),
                row.publishedRevisionId(), 0, 0, false, false, row.bodyMarkdown(), media.images(), media.video());
    }

    private BusinessException missing() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "公开内容关联数据暂不可用");
    }
}
