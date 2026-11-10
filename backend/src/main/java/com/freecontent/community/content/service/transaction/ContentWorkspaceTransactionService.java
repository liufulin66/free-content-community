package com.freecontent.community.content.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.dto.RevisionView;
import com.freecontent.community.content.dto.WorkspacePage;
import com.freecontent.community.content.mapper.ContentWorkspaceMapper;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.freecontent.community.user.model.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.math.BigInteger;

/** 账号与角色共享锁保护授权；详情和分页共用投影检查，列表按页批量读取修订及引用。 */
@Service
public class ContentWorkspaceTransactionService {
    private static final Set<String> WORKING_STATUSES = Set.of("DRAFT", "IN_REVIEW");
    private static final Set<String> PUBLISHED_STATUSES = Set.of("APPROVED");
    private final ContentWorkspaceMapper contents;
    private final UserAccountMapper users;
    private final UserRoleMapper roles;
    public ContentWorkspaceTransactionService(ContentWorkspaceMapper contents, UserAccountMapper users, UserRoleMapper roles) {
        this.contents = contents;
        this.users = users;
        this.roles = roles;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContentWorkspace read(String contentId, SessionPrincipal principal) {
        authorize(principal);
        var content = contents.findOwned(contentId, principal.user().id());
        if (content == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (content.publicationStatus().equals("PUBLISHED") && content.publishedRevisionId() == null) throw unavailable();
        var working = revision(content.workingRevisionId(), content.id(), WORKING_STATUSES);
        var published = revision(content.publishedRevisionId(), content.id(), PUBLISHED_STATUSES);
        return view(content,working,published);
    }

    @Transactional(readOnly = true,isolation = Isolation.REPEATABLE_READ)
    public WorkspacePage list(BigInteger page,int pageSize,String lifecycle,String publicationStatus,String kind,SessionPrincipal principal) {
        authorize(principal);
        String owner = principal.user().id();
        long total = contents.countOwned(owner,lifecycle,publicationStatus,kind);
        BigInteger offset = page.subtract(BigInteger.ONE).multiply(BigInteger.valueOf(pageSize));
        if (offset.compareTo(BigInteger.valueOf(total)) >= 0) return new WorkspacePage(List.of(),page,pageSize,total);
        var rows = contents.pageOwned(owner,lifecycle,publicationStatus,kind,offset.longValueExact(),pageSize);
        var batch = loadRevisions(rows);
        var items = rows.stream().map(content -> view(content,
                revision(content.workingRevisionId(),content.id(),WORKING_STATUSES,batch),
                revision(content.publishedRevisionId(),content.id(),PUBLISHED_STATUSES,batch))).toList();
        return new WorkspacePage(items,page,pageSize,total);
    }

    private record RevisionBatch(Map<String,ContentWorkspaceMapper.RevisionRow> rows,
            Map<String,List<ContentWorkspaceMapper.AssetReference>> assets,Map<String,List<String>> tags) { }

    private RevisionBatch loadRevisions(List<ContentWorkspaceMapper.ContentRow> contentsPage) {
        var ids = new LinkedHashSet<String>();
        for (var row : contentsPage) {
            if (row.workingRevisionId() != null) ids.add(row.workingRevisionId());
            if (row.publishedRevisionId() != null) ids.add(row.publishedRevisionId());
        }
        var rows = new HashMap<String,ContentWorkspaceMapper.RevisionRow>();
        var assets = new HashMap<String,List<ContentWorkspaceMapper.AssetReference>>();
        var tags = new HashMap<String,List<String>>();
        if (ids.isEmpty()) return new RevisionBatch(rows,assets,tags);
        for (var row : contents.findRevisions(contentsPage.stream().map(ContentWorkspaceMapper.ContentRow::id).toList(),List.copyOf(ids))) rows.put(row.id(),row);
        if (!rows.isEmpty()) {
            var loadedIds = List.copyOf(rows.keySet());
            for (var asset : contents.findAssetsForRevisions(loadedIds)) {
                assets.computeIfAbsent(asset.revisionId(),key -> new ArrayList<>()).add(new ContentWorkspaceMapper.AssetReference(asset.assetId(),asset.usageType(),asset.position()));
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

    private ContentWorkspace view(ContentWorkspaceMapper.ContentRow content,RevisionView working,RevisionView published) {
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

    private RevisionView view(ContentWorkspaceMapper.RevisionRow row,List<ContentWorkspaceMapper.AssetReference> assets,List<String> tags) {
        String cover = scalarAsset(assets, "COVER");
        String video = scalarAsset(assets, "VIDEO");
        var images = assets.stream().filter(a -> a.usageType().equals("IMAGE")).map(ContentWorkspaceMapper.AssetReference::assetId).toList();
        return new RevisionView(row.id(), row.contentId(), row.revisionNumber(), row.status(), row.title(), row.bodyMarkdown(), cover, images,
                video, tags, instant(row.createdAt()), instant(row.updatedAt()));
    }
    private ContentWorkspaceMapper.RevisionRow checkedRevision(ContentWorkspaceMapper.RevisionRow row,String contentId,Set<String> allowedStatuses) {
        if (row == null || !contentId.equals(row.contentId()) || !allowedStatuses.contains(row.status())) throw unavailable();
        return row;
    }
    private Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "内容工作区关联数据暂不可用"); }

    private String scalarAsset(List<ContentWorkspaceMapper.AssetReference> references, String usage) {
        var ids = references.stream().filter(a -> a.usageType().equals(usage)).map(ContentWorkspaceMapper.AssetReference::assetId).toList();
        if (ids.size() > 1) throw unavailable();
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void authorize(SessionPrincipal principal) {
        UserAccount account = users.findByIdForShare(principal.user().id());
        if (account == null || account.getAuthVersion() == null || account.getAuthVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.getAccountStatus() == UserAccount.Status.DISABLED) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.getEmailVerified()) || !roles.findRolesForShare(account.getId()).contains(UserRole.USER)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "当前账号不具备工作区读取权限");
        }
    }
}
