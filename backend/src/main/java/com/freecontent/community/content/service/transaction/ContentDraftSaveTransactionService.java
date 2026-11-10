package com.freecontent.community.content.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.mapper.ContentDraftSaveMapper;
import com.freecontent.community.content.mapper.ContentWorkspaceMapper;
import com.freecontent.community.content.validation.ContentDraftValidator;
import com.freecontent.community.content.validation.ContentReferenceValidator;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.*;
import com.freecontent.community.user.model.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.List;
import java.util.Set;

/** 预检和写入各自持有短事务，Redis计数在两者之间；写入必须重新复核版本和状态。 */
@Service
public class ContentDraftSaveTransactionService {
    private static final Set<ErrorCode> COUNTED_REJECTIONS = Set.of(ErrorCode.RESOURCE_NOT_FOUND,ErrorCode.CONTENT_VERSION_CONFLICT,
            ErrorCode.CONTENT_NOT_EDITABLE,ErrorCode.DRAFT_NOT_EDITABLE);
    private final ContentDraftSaveMapper writes;
    private final ContentWorkspaceTransactionService workspaces;
    private final ContentDraftValidator validator;
    private final UserAccountMapper users;
    private final UserRoleMapper roles;
    private final Clock clock;
    private final ContentReferenceValidator references;
    public ContentDraftSaveTransactionService(ContentDraftSaveMapper writes,ContentWorkspaceTransactionService workspaces,
            ContentDraftValidator validator,UserAccountMapper users,UserRoleMapper roles,Clock clock,ContentReferenceValidator references) {
        this.writes = writes;
        this.workspaces = workspaces;
        this.validator = validator;
        this.users = users;
        this.roles = roles;
        this.clock = clock;
        this.references = references;
    }

    private record DraftChange(ContentWorkspaceMapper.ContentRow content,ContentWorkspaceMapper.RevisionRow revision,
            ContentWorkspace before,CreateContentRequest merged) { }

    @Transactional
    public BusinessException preflight(String contentId,EditDraftRequest patch,SessionPrincipal principal) {
        try { inspect(contentId,patch,principal); return null; }
        catch (BusinessException ex) {
            if (COUNTED_REJECTIONS.contains(ex.errorCode())) return ex;
            throw ex;
        }
    }

    @Transactional
    public ContentWorkspace save(String contentId,EditDraftRequest patch,SessionPrincipal principal) {
        var change = inspect(contentId,patch,principal);
        var content = change.content(); var revision = change.revision(); var before = change.before(); var merged = change.merged();
        if (sameValues(before.workingRevision(),merged)) return before;
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (writes.advanceVersion(contentId,principal.user().id(),content.version(),revision.id(),now) != 1) throw conflict(content.version());
        if (writes.updateDraft(revision.id(),contentId,merged,now) != 1) throw new BusinessException(ErrorCode.DRAFT_NOT_EDITABLE);
        replaceReferences(revision.id(),before.workingRevision(),merged);
        return workspaces.read(contentId,principal);
    }

    private DraftChange inspect(String contentId,EditDraftRequest patch,SessionPrincipal principal) {
        authorize(principal);
        var content = writes.lockOwned(contentId,principal.user().id());
        if (content == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (content.version() != patch.getExpectedVersion()) throw conflict(content.version());
        if (!content.lifecycle().equals("ACTIVE")) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE);
        if (content.workingRevisionId() == null || content.activeReviewId() != null) throw new BusinessException(ErrorCode.DRAFT_NOT_EDITABLE);
        var revision = writes.lockRevision(content.workingRevisionId(),contentId);
        if (revision == null) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,"草稿关联数据暂不可用");
        if (!revision.status().equals("DRAFT")) throw new BusinessException(ErrorCode.DRAFT_NOT_EDITABLE);
        var before = workspaces.read(contentId,principal);
        var merged = merge(before,patch);
        validator.validate(merged);
        references.validate(merged,principal.user().id());
        if (content.version() == Integer.MAX_VALUE && !sameValues(before.workingRevision(),merged)) throw conflict(content.version());
        return new DraftChange(content,revision,before,merged);
    }

    private CreateContentRequest merge(ContentWorkspace workspace,EditDraftRequest patch) {
        var old = workspace.workingRevision(); var result = new CreateContentRequest(); result.setKind(workspace.kind());
        result.setTitle(patch.hasTitle() ? patch.getTitle() : old.title());
        result.setBodyMarkdown(patch.hasBodyMarkdown() ? patch.getBodyMarkdown() : old.bodyMarkdown());
        result.setCoverAssetId(patch.hasCoverAssetId() ? patch.getCoverAssetId() : old.coverAssetId());
        result.setImageAssetIds(patch.hasImageAssetIds() ? patch.getImageAssetIds() : old.imageAssetIds());
        result.setVideoAssetId(patch.hasVideoAssetId() ? patch.getVideoAssetId() : old.videoAssetId());
        result.setTagIds(patch.hasTagIds() ? patch.getTagIds() : old.tagIds()); return result;
    }
    private BusinessException conflict(int version) { return new BusinessException(ErrorCode.CONTENT_VERSION_CONFLICT,"内容已变更，请重新读取工作区",version); }
    private boolean sameValues(RevisionView old,CreateContentRequest value) {
        return Objects.equals(old.title(),value.getTitle()) && Objects.equals(old.bodyMarkdown(),value.getBodyMarkdown())
                && Objects.equals(old.coverAssetId(),value.getCoverAssetId()) && Objects.equals(old.imageAssetIds(),value.getImageAssetIds())
                && Objects.equals(old.videoAssetId(),value.getVideoAssetId()) && Objects.equals(old.tagIds(),value.getTagIds());
    }
    private void replaceReferences(String revision,RevisionView old,CreateContentRequest value) {
        if (!Objects.equals(old.coverAssetId(),value.getCoverAssetId())) replaceAssets(revision,"COVER",single(value.getCoverAssetId()));
        if (!Objects.equals(old.imageAssetIds(),value.getImageAssetIds())) replaceAssets(revision,"IMAGE",value.getImageAssetIds());
        if (!Objects.equals(old.videoAssetId(),value.getVideoAssetId())) replaceAssets(revision,"VIDEO",single(value.getVideoAssetId()));
        if (!Objects.equals(old.tagIds(),value.getTagIds())) {
            writes.deleteTags(revision);
            for (int i = 0; i < value.getTagIds().size(); i++) writes.insertTag(revision,value.getTagIds().get(i),i);
        }
    }
    private void replaceAssets(String revision,String usage,List<String> ids) {
        writes.deleteAssets(revision,usage);
        for (int i = 0; i < ids.size(); i++) writes.insertAsset(revision,ids.get(i),usage,i);
    }
    private List<String> single(String id) { return id == null ? List.of() : List.of(id); }
    private void authorize(SessionPrincipal principal) {
        UserAccount account = users.findByIdForUpdate(principal.user().id());
        if (account == null || account.getAuthVersion() == null || account.getAuthVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (account.getAccountStatus() != UserAccount.Status.ACTIVE) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.getEmailVerified()) || !roles.findRolesForShare(account.getId()).contains(UserRole.USER)) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
    }
}
