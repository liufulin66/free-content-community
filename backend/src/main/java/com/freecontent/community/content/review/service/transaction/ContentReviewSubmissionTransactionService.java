package com.freecontent.community.content.review.service.transaction;

import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.content.review.dto.ReviewView;
import com.freecontent.community.content.review.dto.SubmitReviewRequest;
import com.freecontent.community.content.review.mapper.ContentReviewSubmissionMapper;
import com.freecontent.community.content.review.mapper.ContentReviewSubmissionRequestMapper;
import com.freecontent.community.content.mapper.ContentLockMapper;
import com.freecontent.community.content.service.read.ContentWorkspaceReader;
import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.content.model.persistence.*;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.review.entity.ContentReviewSubmissionRequestRecord;
import com.freecontent.community.content.review.validation.ContentReviewSubmissionValidator;
import com.freecontent.community.user.model.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Redis计数位于领取和提交之间；最终事务原子提交审核、冻结快照、事件和幂等结果。 */
@Service
public class ContentReviewSubmissionTransactionService {
    private final ContentLockMapper locks;
    private final ContentReviewSubmissionMapper writes;
    private final AccountAccessService accounts;
    private final JsonMapper json;
    private final Clock clock;
    private final ContentWorkspaceReader reader;
    private final ContentReviewSubmissionValidator validator;
    private final ContentReviewSubmissionRequestMapper requests;
    public ContentReviewSubmissionTransactionService(ContentLockMapper locks, ContentReviewSubmissionMapper writes,
            AccountAccessService accounts, JsonMapper json, Clock clock,
            ContentWorkspaceReader reader, ContentReviewSubmissionValidator validator, ContentReviewSubmissionRequestMapper requests) {
        this.locks = locks; this.writes = writes; this.accounts = accounts; this.json = json; this.clock = clock;
        this.reader = reader; this.validator = validator;
        this.requests = requests;
    }

    public record Claim(ContentReviewSubmissionRequestRecord request, SubmitReviewRequest input, ReviewResult replay) { }

    /** 与现有创建流程一致：账号锁串行领取，避免缺失记录上的间隙锁。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim(String contentId, SubmitReviewRequest input, String keyHash, String fingerprint, SessionPrincipal principal) {
        String actor = authorize(principal); LocalDateTime now = now();
        var record = requests.lockByKey(actor, keyHash);
        if (record != null && !record.getExpiresAt().isAfter(now)) { requests.deleteById(record.getId()); record = null; }
        if (record != null) {
            if (!fingerprint.equals(record.getFingerprint())) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            if ("SUCCEEDED".equals(record.getState())) return new Claim(record, input, replay(record, actor));
            if (record.getLeaseUntil() != null && record.getLeaseUntil().isAfter(now))
                throw inProgress(Math.max(1, (Duration.between(now, record.getLeaseUntil()).toMillis() + 999) / 1000));
            record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60));
            if (requests.renew(record.getId(), record.getLeaseToken(), record.getLeaseUntil()) != 1) throw inProgress(1);
        } else {
            record = new ContentReviewSubmissionRequestRecord(); record.setId(id()); record.setCreatedBy(actor);
            record.setKeyHash(keyHash); record.setFingerprint(fingerprint); record.setState("PROCESSING"); record.setContentId(contentId);
            record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60)); record.setCreatedAt(now); record.setExpiresAt(now.plusHours(24));
            requests.insert(record);
        }
        return new Claim(record, input, null);
    }

    private ReviewResult replay(ContentReviewSubmissionRequestRecord record, String actor) {
        if (locks.lockOwned(record.getContentId(), actor) == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        ReviewResult result;
        try { result = json.readValue(record.getResultSnapshot(), ReviewResult.class); }
        catch (RuntimeException ex) { throw unavailable(); }
        if (result == null || result.review() == null || !Objects.equals(record.getReviewId(), result.review().id())
                || !record.getContentId().equals(result.review().contentId()) || result.contentVersion() < 1
                || writes.countReview(record.getReviewId(), record.getContentId(), result.review().revisionId()) != 1) throw unavailable();
        return result;
    }

    @Transactional
    public void release(Claim claim) { requests.release(claim.request().getId(), claim.request().getLeaseToken()); }

    @Transactional
    public ReviewResult finish(Claim claim, SessionPrincipal principal) {
        String actor = authorize(principal);
        var record = requests.lockByKey(actor, claim.request().getKeyHash()); LocalDateTime checkedAt = now();
        if (record == null || !record.getId().equals(claim.request().getId()) || !"PROCESSING".equals(record.getState())
                || !Objects.equals(record.getLeaseToken(), claim.request().getLeaseToken()) || record.getLeaseUntil() == null
                || !record.getLeaseUntil().isAfter(checkedAt) || !record.getExpiresAt().isAfter(checkedAt)) throw inProgress(1);
        var result = submitDraft(record.getContentId(), claim.input(), actor);
        if (requests.complete(record.getId(), record.getLeaseToken(), result.review().id(), json.writeValueAsString(result), now()) != 1) throw inProgress(1);
        return result;
    }

    private ReviewResult submitDraft(String contentId, SubmitReviewRequest input, String actor) {
        var content = locks.lockOwned(contentId, actor);
        if (content == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (content.version() != input.getExpectedVersion() || content.version() == Integer.MAX_VALUE)
            throw new BusinessException(ErrorCode.CONTENT_VERSION_CONFLICT, "内容已变更，请重新读取工作区", content.version());
        if (!"ACTIVE".equals(content.lifecycle())) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE);
        if (content.activeReviewId() != null || writes.findPending(contentId) != null) throw new BusinessException(ErrorCode.REVIEW_ALREADY_PENDING);
        var revision = locks.lockRevision(input.getRevisionId(), contentId);
        if (revision == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (!Objects.equals(content.workingRevisionId(), revision.id()) || !"DRAFT".equals(revision.status())) throw new BusinessException(ErrorCode.DRAFT_NOT_EDITABLE);
        validator.validate(reader.workspace(content), actor);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDateTime timestamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        String purpose = "TAKEN_DOWN".equals(content.publicationStatus()) ? "REMEDIATION" : content.publishedRevisionId() == null ? "INITIAL" : "UPDATE";
        var review = new ReviewView(id(), contentId, revision.id(), purpose, "PENDING", 1, now, null, null, null);
        if (writes.insertReview(new ReviewInsert(review.id(), review.contentId(), review.revisionId(), review.purpose()), timestamp) != 1 || writes.freeze(contentId, revision.id(), timestamp) != 1
                || writes.attach(contentId, actor, content.version(), revision.id(), review.id(), timestamp) != 1) throw unavailable();
        var payload = Map.of("eventVersion", 1, "contentId", contentId, "revisionId", revision.id(), "reviewId", review.id(),
                "authorId", actor, "contentVersion", content.version() + 1, "purpose", purpose, "occurredAt", now);
        if (writes.insertEvent(id(), new ReviewEventReference(review.id(), review.contentId(), review.revisionId()), content.version() + 1, json.writeValueAsString(payload), timestamp) != 1) throw unavailable();
        return new ReviewResult(review, content.version() + 1);
    }

    private String authorize(SessionPrincipal principal) {
        var account = accounts.findByIdForUpdate(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (!account.active()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.emailVerified()) || !accounts.findRolesForShare(account.id()).contains(UserRole.USER)) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        return account.id();
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    private BusinessException inProgress(long wait) { return new BusinessException(ErrorCode.REQUEST_IN_PROGRESS, "相同审核提交请求正在处理", null, wait, List.of()); }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "审核提交数据服务暂不可用"); }
}
