package com.freecontent.community.content.workspace.service.transaction;

import com.freecontent.community.content.workspace.dto.CreateContentRequest;
import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.content.model.persistence.*;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.content.workspace.entity.ContentCreationRequestRecord;
import com.freecontent.community.content.workspace.mapper.ContentCreationRequestMapper;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.workspace.mapper.ContentCreationMapper;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.content.validation.ContentReferenceValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 短事务领取幂等租约；账号、角色、幂等记录、素材按固定顺序锁定，最终结果原子提交。 */
@Service
public class ContentCreationTransactionService {
    private final ContentCreationMapper contents;
    private final JsonMapper json;
    private final Clock clock;
    private final AccountAccessService accounts;
    private final ContentReferenceValidator references;
    private final ContentCreationRequestMapper requests;
    public ContentCreationTransactionService(ContentCreationMapper contents, JsonMapper json, Clock clock,
            AccountAccessService accounts, ContentReferenceValidator references, ContentCreationRequestMapper requests) {
        this.contents = contents;
        this.json = json;
        this.clock = clock;
        this.accounts = accounts;
        this.references = references;
        this.requests = requests;
    }

    public record Claim(ContentCreationRequestRecord request, CreateContentRequest input, ContentWorkspace replay) { }

    // 缺失幂等键不加间隙锁，同一作者通过账号行锁串行领取。
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim(CreateContentRequest input, String keyHash, String fingerprint, SessionPrincipal principal) {
        String actor = authorize(principal); LocalDateTime now = now();
        var record = requests.lockByKey(actor, keyHash);
        if (record != null && !record.getExpiresAt().isAfter(now)) { requests.deleteById(record.getId()); record = null; }
        if (record != null) {
            if (!Objects.equals(fingerprint, record.getFingerprint())) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            if ("SUCCEEDED".equals(record.getState())) {
                return new Claim(record, input, replayResult(record, actor));
            }
            if (record.getLeaseUntil() != null && record.getLeaseUntil().isAfter(now)) {
                throw inProgress(Math.max(1, (Duration.between(now, record.getLeaseUntil()).toMillis() + 999) / 1000));
            }
            record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60));
            if (requests.renew(record.getId(), record.getLeaseToken(), record.getLeaseUntil()) != 1) throw inProgress(1);
        } else {
            record = new ContentCreationRequestRecord(); record.setId(id()); record.setCreatedBy(actor);
            record.setKeyHash(keyHash); record.setFingerprint(fingerprint); record.setState("PROCESSING");
            record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60)); record.setCreatedAt(now); record.setExpiresAt(now.plusHours(24));
            requests.insert(record);
        }
        return new Claim(record, input, null);
    }

    @Transactional
    public ContentWorkspace finish(Claim claim, SessionPrincipal principal) {
        String actor = authorize(principal); LocalDateTime checkedAt = now();
        var record = requests.lockByKey(actor, claim.request().getKeyHash());
        if (record == null || !record.getId().equals(claim.request().getId()) || !"PROCESSING".equals(record.getState())
                || !Objects.equals(record.getLeaseToken(), claim.request().getLeaseToken()) || record.getLeaseUntil() == null
                || !record.getLeaseUntil().isAfter(checkedAt) || !record.getExpiresAt().isAfter(checkedAt)) throw inProgress(1);
        CreateContentRequest input = claim.input();
        references.validate(input.toDraftData(), principal.user().id());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDateTime timestamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        String contentId = id();
        var draft = new RevisionView(id(), contentId, 1, "DRAFT", input.getTitle(), input.getBodyMarkdown(), input.getCoverAssetId(),
                input.getImageAssetIds(), input.getVideoAssetId(), input.getTagIds(), now, now);
        var view = new ContentWorkspace(contentId, principal.user().id(), input.getKind(), "ACTIVE", "UNPUBLISHED", 1,
                draft, null, null, now, now, null);
        if (contents.insertContent(new ContentInsert(view.id(), view.authorId(), view.kind()), timestamp) != 1 || contents.insertRevision(new RevisionInsert(draft.id(), draft.contentId(), draft.title(), draft.bodyMarkdown()), timestamp) != 1) throw unavailable();
        persistReferences(draft);
        if (contents.attachWorkingRevision(contentId, draft.id()) != 1) throw unavailable();
        if (requests.complete(record.getId(), record.getLeaseToken(), contentId, json.writeValueAsString(view), now()) != 1) throw inProgress(1);
        return view;
    }

    @Transactional
    public void release(Claim claim) { requests.release(claim.request().getId(), claim.request().getLeaseToken()); }

    private ContentWorkspace replayResult(ContentCreationRequestRecord record, String actor) {
        if (!actor.equals(contents.findAuthor(record.getContentId()))) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        ContentWorkspace result;
        try { result = json.readValue(record.getResultSnapshot(), ContentWorkspace.class); }
        catch (RuntimeException ex) { throw unavailable(); }
        if (result == null || !actor.equals(result.authorId()) || !record.getContentId().equals(result.id())) throw unavailable();
        return result;
    }

    private void persistReferences(RevisionView draft) {
        if (draft.coverAssetId() != null) contents.insertAsset(draft.id(), draft.coverAssetId(), "COVER", 0);
        for (int i = 0; i < draft.imageAssetIds().size(); i++) contents.insertAsset(draft.id(), draft.imageAssetIds().get(i), "IMAGE", i);
        if (draft.videoAssetId() != null) contents.insertAsset(draft.id(), draft.videoAssetId(), "VIDEO", 0);
        for (int i = 0; i < draft.tagIds().size(); i++) contents.insertTag(draft.id(), draft.tagIds().get(i), i);
    }

    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "内容创建结果暂不可用"); }
    private BusinessException inProgress(long wait) { return new BusinessException(ErrorCode.REQUEST_IN_PROGRESS, "相同内容创建请求正在处理", null, wait, List.of()); }

    private String authorize(SessionPrincipal principal) {
        AccountSnapshot account = accounts.findByIdForUpdate(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (!account.active()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.emailVerified())) throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
        if (!accounts.findRolesForShare(account.id()).contains(UserRole.USER)) throw new BusinessException(ErrorCode.PERMISSION_DENIED, "当前账号不具备创作资格");
        return account.id();
    }

}
