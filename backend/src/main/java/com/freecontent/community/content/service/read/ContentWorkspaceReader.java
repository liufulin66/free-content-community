package com.freecontent.community.content.service.read;

import com.freecontent.community.content.model.persistence.RevisionRow;
import com.freecontent.community.content.model.persistence.ContentRow;
import com.freecontent.community.content.model.persistence.AssetReference;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.mapper.ContentReadMapper;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** 仅负责已授权事务内的数据读取和投影；授权由本人或后台事务入口分别完成。 */
@Component
public class ContentWorkspaceReader {
    private static final Set<String> WORKING_STATUSES = Set.of("DRAFT", "IN_REVIEW");
    private static final Set<String> PUBLISHED_STATUSES = Set.of("APPROVED");
    private static final Set<String> FROZEN_STATUSES = Set.of("IN_REVIEW", "APPROVED", "REJECTED", "CANCELLED");
    private final ContentReadMapper contents;
    public ContentWorkspaceReader(ContentReadMapper contents) { this.contents = contents; }

    public ContentRow findContent(String contentId) {
        return contents.findById(contentId);
    }

    public ContentWorkspace workspace(ContentRow content) {
        var working = revision(content.workingRevisionId(), content.id(), WORKING_STATUSES);
        var published = revision(content.publishedRevisionId(), content.id(), PUBLISHED_STATUSES);
        return view(content, working, published);
    }

    public List<ContentWorkspace> workspaces(List<ContentRow> rows) {
        var batch = loadRevisions(rows);
        return rows.stream().map(content -> view(content,
                revision(content.workingRevisionId(),content.id(),WORKING_STATUSES,batch),
                revision(content.publishedRevisionId(),content.id(),PUBLISHED_STATUSES,batch))).toList();
    }

    public RevisionView frozenRevision(String contentId, String revisionId) {
        if (revisionId == null) throw unavailable();
        return revision(revisionId, contentId, FROZEN_STATUSES);
    }

    private record RevisionBatch(Map<String,RevisionRow> rows,
            Map<String,List<AssetReference>> assets,Map<String,List<String>> tags) { }

    private RevisionBatch loadRevisions(List<ContentRow> contentsPage) {
        var ids = new LinkedHashSet<String>();
        for (var row : contentsPage) {
            if (row.workingRevisionId() != null) ids.add(row.workingRevisionId());
            if (row.publishedRevisionId() != null) ids.add(row.publishedRevisionId());
        }
        var rows = new HashMap<String,RevisionRow>();
        var assets = new HashMap<String,List<AssetReference>>();
        var tags = new HashMap<String,List<String>>();
        if (ids.isEmpty()) return new RevisionBatch(rows,assets,tags);
        for (var row : contents.findRevisions(contentsPage.stream().map(ContentRow::id).toList(),List.copyOf(ids))) rows.put(row.id(),row);
        if (!rows.isEmpty()) {
            var loadedIds = List.copyOf(rows.keySet());
            for (var asset : contents.findAssetsForRevisions(loadedIds)) {
                assets.computeIfAbsent(asset.revisionId(),key -> new ArrayList<>()).add(new AssetReference(asset.assetId(),asset.usageType(),asset.position()));
            }
            for (var tag : contents.findTagsForRevisions(loadedIds)) tags.computeIfAbsent(tag.revisionId(),key -> new ArrayList<>()).add(tag.tagId());
        }
        return new RevisionBatch(rows,assets,tags);
    }

    private RevisionView revision(String revisionId,String contentId,Set<String> allowedStatuses,RevisionBatch batch) {
        if (revisionId == null) return null;
        var row = checkedRevision(batch.rows().get(revisionId),contentId,allowedStatuses);
        return view(row,batch.assets().getOrDefault(revisionId,List.of()),batch.tags().getOrDefault(revisionId,List.of()));
    }

    private ContentWorkspace view(ContentRow content,RevisionView working,RevisionView published) {
        if (content.publicationStatus().equals("PUBLISHED") && content.publishedRevisionId() == null) throw unavailable();
        return new ContentWorkspace(content.id(),content.authorId(),content.kind(),content.lifecycle(),content.publicationStatus(),content.version(),working,published,
                content.activeReviewId(),instant(content.createdAt()),instant(content.updatedAt()),instant(content.deletedAt()));
    }

    private RevisionView revision(String revisionId, String contentId, Set<String> allowedStatuses) {
        if (revisionId == null) return null;
        var row = checkedRevision(contents.findRevision(revisionId,contentId),contentId,allowedStatuses);
        var assets = contents.findAssets(revisionId);
        return view(row,assets,contents.findTagIds(revisionId));
    }

    private RevisionView view(RevisionRow row,List<AssetReference> assets,List<String> tags) {
        String cover = scalarAsset(assets, "COVER");
        String video = scalarAsset(assets, "VIDEO");
        var images = assets.stream().filter(a -> a.usageType().equals("IMAGE")).map(AssetReference::assetId).toList();
        return new RevisionView(row.id(), row.contentId(), row.revisionNumber(), row.status(), row.title(), row.bodyMarkdown(), cover, images,
                video, tags, instant(row.createdAt()), instant(row.updatedAt()));
    }
    private RevisionRow checkedRevision(RevisionRow row,String contentId,Set<String> allowedStatuses) {
        if (row == null || !contentId.equals(row.contentId()) || !allowedStatuses.contains(row.status())) throw unavailable();
        return row;
    }
    private Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "内容工作区关联数据暂不可用"); }

    private String scalarAsset(List<AssetReference> references, String usage) {
        var ids = references.stream().filter(a -> a.usageType().equals(usage)).map(AssetReference::assetId).toList();
        if (ids.size() > 1) throw unavailable();
        return ids.isEmpty() ? null : ids.get(0);
    }

}
