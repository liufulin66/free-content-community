package com.freecontent.community.content.review.service.transaction;

import com.freecontent.community.content.review.dto.ReviewDecisionRequest;
import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.content.review.dto.ReviewView;
import com.freecontent.community.content.review.mapper.ReviewDecisionMapper;
import com.freecontent.community.content.review.mapper.ReviewDecisionRequestMapper;
import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.content.model.persistence.*;
import com.freecontent.community.content.model.persistence.ReviewRow;
import com.freecontent.community.content.model.persistence.ContentRow;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.review.entity.ReviewDecisionRequestRecord;
import com.freecontent.community.content.service.read.ContentWorkspaceReader;
import com.freecontent.community.content.review.validation.ContentReviewMediaValidator;
import com.freecontent.community.user.model.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 领取和最终提交分属短事务；最终事务按账号→幂等→内容→审核→修订→素材加锁并原子落库。 */
@Service
public class ReviewDecisionTransactionService {
    private final ReviewDecisionMapper writes;
    private final AccountAccessService accounts;
    private final Clock clock;
    private final JsonMapper json;
    private final ReviewDecisionRequestMapper requests;
    private final ContentWorkspaceReader reader;
    private final ContentReviewMediaValidator media;
    public ReviewDecisionTransactionService(ReviewDecisionMapper writes, AccountAccessService accounts, Clock clock, JsonMapper json,
            ReviewDecisionRequestMapper requests, ContentWorkspaceReader reader, ContentReviewMediaValidator media) {
        this.writes = writes; this.accounts = accounts; this.clock = clock; this.json = json;
        this.requests = requests;
        this.reader = reader; this.media = media;
    }

    public record Claim(ReviewDecisionRequestRecord request, ReviewDecisionRequest input, ReviewResult replay) { }
    private record Target(ContentRow content, ReviewRow review) { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim(String reviewId, ReviewDecisionRequest input, String keyHash, String fingerprint, SessionPrincipal principal) {
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
            record = new ReviewDecisionRequestRecord(); record.setId(id()); record.setCreatedBy(actor); record.setReviewId(reviewId);
            record.setKeyHash(keyHash); record.setFingerprint(fingerprint); record.setState("PROCESSING");
            record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60)); record.setCreatedAt(now); record.setExpiresAt(now.plusHours(24));
            requests.insert(record);
        }
        return new Claim(record, input, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReviewResult finish(Claim claim, SessionPrincipal principal, String requestId) {
        String actor = authorize(principal); LocalDateTime checkedAt = now();
        var record = requests.lockByKey(actor, claim.request().getKeyHash());
        if (record == null || !record.getId().equals(claim.request().getId()) || !"PROCESSING".equals(record.getState())
                || !Objects.equals(record.getLeaseToken(), claim.request().getLeaseToken()) || record.getLeaseUntil() == null
                || !record.getLeaseUntil().isAfter(checkedAt) || !record.getExpiresAt().isAfter(checkedAt)) throw inProgress(1);
        var result = applyDecision(record.getReviewId(), claim.input(), actor, requestId);
        if (requests.complete(record.getId(), record.getLeaseToken(), result.review().contentId(), json.writeValueAsString(result), now()) != 1) throw inProgress(1);
        return result;
    }

    @Transactional
    public void release(Claim claim) { requests.release(claim.request().getId(), claim.request().getLeaseToken()); }

    private ReviewResult replay(ReviewDecisionRequestRecord record, String actor) {
        var target = target(record.getReviewId(), actor); ReviewResult result;
        try { result = json.readValue(record.getResultSnapshot(), ReviewResult.class); }
        catch (RuntimeException ex) { throw unavailable(); }
        if (result == null || result.review() == null || !record.getReviewId().equals(result.review().id())
                || !Objects.equals(record.getContentId(), result.review().contentId()) || !target.review().revisionId().equals(result.review().revisionId())
                || !actor.equals(result.review().reviewerId()) || !Set.of("APPROVED", "REJECTED").contains(result.review().state()) || result.contentVersion() < 1) throw unavailable();
        return result;
    }

    private Target target(String reviewId, String actor) {
        String contentId = writes.findContentId(reviewId); if (contentId == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var content = writes.lockContent(contentId); if (content == null) throw unavailable();
        var review = writes.lockReview(reviewId, contentId); if (review == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (actor.equals(content.authorId())) throw new BusinessException(ErrorCode.PERMISSION_DENIED, "不能审核自己的作品");
        return new Target(content, review);
    }

    private ReviewResult applyDecision(String reviewId, ReviewDecisionRequest input, String actor, String requestId) {
        var target = target(reviewId, actor); var content = target.content(); var review = target.review(); String contentId = content.id();
        if (content.version() != input.getExpectedContentVersion() || content.version() == Integer.MAX_VALUE)
            throw new BusinessException(ErrorCode.CONTENT_VERSION_CONFLICT, "内容版本已变化，请重新读取审核详情", content.version());
        if (review.version() != input.getExpectedReviewVersion() || review.version() == Integer.MAX_VALUE)
            throw new BusinessException(ErrorCode.REVIEW_VERSION_CONFLICT, "审核版本已变化，请重新读取审核详情", review.version());
        if (!content.lifecycle().equals("ACTIVE")) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE);
        if (!review.state().equals("PENDING") || !Objects.equals(content.activeReviewId(), reviewId)
                || !Objects.equals(content.workingRevisionId(), review.revisionId())) throw new BusinessException(ErrorCode.REVIEW_NOT_PENDING);
        if (!"IN_REVIEW".equals(writes.lockRevisionStatus(review.revisionId(), contentId))) throw new BusinessException(ErrorCode.REVIEW_NOT_PENDING);
        boolean approved = input.getDecision() == ReviewDecisionRequest.Decision.APPROVE;
        if (approved) {
            validatePurpose(content, review.purpose());
            media.validate(content.kind(), reader.frozenRevision(contentId, review.revisionId()), content.authorId());
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS); LocalDateTime timestamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        String state = approved ? "APPROVED" : "REJECTED";
        var resultReview = new ReviewView(review.id(), contentId, review.revisionId(), review.purpose(), state, review.version() + 1,
                review.submittedAt().toInstant(ZoneOffset.UTC), now, actor, input.getReason());
        if (writes.decideRevision(review.revisionId(), contentId, state, timestamp) != 1
                || writes.decideReview(reviewId, contentId, review.revisionId(), review.version(), state, actor, input.getReason(), timestamp) != 1
                || writes.updateContent(contentId, content.version(), review.revisionId(), reviewId, approved, timestamp) != 1) throw unavailable();
        var audit = new ReviewDecisionMapper.Audit(id(), actor, reviewId, contentId, review.revisionId(), input.getDecision().name(), input.getReason(),
                content.version(), content.version() + 1, review.version(), review.version() + 1, requestId, timestamp);
        if (writes.insertAudit(audit) != 1) throw unavailable();
        persistEvents(content, resultReview, input, now);
        return new ReviewResult(resultReview, content.version() + 1);
    }

    private void persistEvents(ContentRow content, ReviewView review, ReviewDecisionRequest input, Instant now) {
        String reviewId = review.id(), contentId = content.id(); boolean approved = input.getDecision() == ReviewDecisionRequest.Decision.APPROVE;
        LocalDateTime timestamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        var payload = new LinkedHashMap<String,Object>();
        payload.put("eventVersion", 1); payload.put("reviewId", reviewId); payload.put("contentId", contentId); payload.put("revisionId", review.revisionId());
        payload.put("contentVersion", content.version() + 1); payload.put("reviewVersion", review.version());
        payload.put("authorId", content.authorId()); payload.put("reviewerId", review.reviewerId()); payload.put("purpose", review.purpose());
        payload.put("decision", input.getDecision().name()); payload.put("reason", input.getReason()); payload.put("occurredAt", now);
        payload.put("publicationStatus", approved ? "PUBLISHED" : content.publicationStatus());
        payload.put("publishedRevisionId", approved ? review.revisionId() : content.publishedRevisionId());
        String encoded = json.writeValueAsString(payload);
        if (writes.insertEvent(id(), new ReviewEventReference(review.id(), review.contentId(), review.revisionId()), content.version() + 1, "CONTENT_REVIEW_DECIDED", encoded, timestamp) != 1) throw unavailable();
        if (approved && writes.insertEvent(id(), new ReviewEventReference(review.id(), review.contentId(), review.revisionId()), content.version() + 1, "CONTENT_PUBLISHED", encoded, timestamp) != 1) throw unavailable();
    }
    private void validatePurpose(ContentRow content, String purpose) {
        boolean valid = switch (purpose) {
            case "INITIAL" -> content.publishedRevisionId() == null && content.publicationStatus().equals("UNPUBLISHED");
            case "UPDATE" -> content.publishedRevisionId() != null && Set.of("PUBLISHED", "WITHDRAWN").contains(content.publicationStatus());
            case "REMEDIATION" -> content.publishedRevisionId() != null && content.publicationStatus().equals("TAKEN_DOWN");
            default -> false;
        };
        if (!valid) throw new BusinessException(ErrorCode.CONTENT_NOT_EDITABLE, "审核用途与内容当前发布状态不匹配");
    }
    private String authorize(SessionPrincipal principal) {
        var account = accounts.findByIdForUpdate(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (!account.active()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.emailVerified()) || accounts.findRolesForShare(account.id()).stream().noneMatch(role -> role == UserRole.MODERATOR || role == UserRole.ADMIN))
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要审核员或管理员的review:decide权限");
        return account.id();
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    private BusinessException inProgress(long wait) { return new BusinessException(ErrorCode.REQUEST_IN_PROGRESS, "相同审核决定请求正在处理", null, wait, List.of()); }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "审核决定数据服务暂不可用"); }
}
