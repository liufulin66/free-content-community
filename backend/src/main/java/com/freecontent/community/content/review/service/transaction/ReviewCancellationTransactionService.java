package com.freecontent.community.content.review.service.transaction;

import com.freecontent.community.content.review.dto.CancelReviewRequest;
import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.content.review.dto.ReviewView;
import com.freecontent.community.content.review.mapper.ReviewCancellationMapper;
import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.content.model.persistence.ReviewRow;
import com.freecontent.community.content.model.persistence.ContentRow;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.user.model.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 与审批共用内容→审核→修订锁顺序；保留快照和公开状态，撤销记录与事件原子提交。 */
@Service
public class ReviewCancellationTransactionService {
    private final ReviewCancellationMapper writes;
    private final AccountAccessService accounts;
    private final Clock clock;
    private final JsonMapper json;
    public ReviewCancellationTransactionService(ReviewCancellationMapper writes, AccountAccessService accounts, Clock clock, JsonMapper json) {
        this.writes = writes; this.accounts = accounts; this.clock = clock; this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReviewResult cancel(String contentId, String reviewId, CancelReviewRequest input, SessionPrincipal principal, String requestId) {
        String actor = authorize(principal);
        var content = writes.lockOwned(contentId, actor); if (content == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var review = writes.lockReview(reviewId, contentId); if (review == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (review.state().equals("CANCELLED") && writes.countOwnCancellation(actor, contentId, reviewId, review.revisionId()) == 1) {
            if (!"CANCELLED".equals(writes.lockRevisionStatus(review.revisionId(), contentId))) throw unavailable();
            return new ReviewResult(new ReviewView(review.id(), contentId, review.revisionId(), review.purpose(), review.state(), review.version(),
                    review.submittedAt().toInstant(ZoneOffset.UTC), review.decidedAt() == null ? null : review.decidedAt().toInstant(ZoneOffset.UTC),
                    review.reviewerId(), review.reason()), content.version());
        }
        if (content.version() != input.getExpectedVersion() || content.version() == Integer.MAX_VALUE)
            throw new BusinessException(ErrorCode.CONTENT_VERSION_CONFLICT, "内容版本已变化，请重新读取工作区", content.version());
        if (review.version() != input.getExpectedReviewVersion() || review.version() == Integer.MAX_VALUE)
            throw new BusinessException(ErrorCode.REVIEW_VERSION_CONFLICT, "审核版本已变化，请重新读取审核状态", review.version());
        if (!content.lifecycle().equals("ACTIVE") || !review.state().equals("PENDING") || !Objects.equals(content.activeReviewId(), reviewId)
                || !Objects.equals(content.workingRevisionId(), review.revisionId())) throw new BusinessException(ErrorCode.REVIEW_NOT_PENDING);
        if (!"IN_REVIEW".equals(writes.lockRevisionStatus(review.revisionId(), contentId))) throw new BusinessException(ErrorCode.REVIEW_NOT_PENDING);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS); LocalDateTime timestamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        if (writes.cancelRevision(review.revisionId(), contentId, timestamp) != 1
                || writes.cancelReview(reviewId, contentId, review.revisionId(), review.version(), timestamp) != 1
                || writes.updateContent(contentId, actor, content.version(), review.revisionId(), reviewId, timestamp) != 1) throw unavailable();
        var audit = new ReviewCancellationMapper.Audit(id(), actor, contentId, reviewId, review.revisionId(), content.version(), content.version() + 1,
                review.version(), review.version() + 1, requestId, timestamp);
        if (writes.insertAudit(audit) != 1) throw unavailable();
        persistEvent(content, review, actor, now);
        return new ReviewResult(new ReviewView(review.id(), contentId, review.revisionId(), review.purpose(), "CANCELLED", review.version() + 1,
                review.submittedAt().toInstant(ZoneOffset.UTC), now, null, null), content.version() + 1);
    }

    private void persistEvent(ContentRow content, ReviewRow review, String actor, Instant now) {
        String contentId = content.id(), reviewId = review.id(); LocalDateTime timestamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        var payload = new LinkedHashMap<String,Object>();
        payload.put("eventVersion", 1); payload.put("actorId", actor); payload.put("contentId", contentId); payload.put("reviewId", reviewId);
        payload.put("revisionId", review.revisionId()); payload.put("contentVersion", content.version() + 1); payload.put("reviewVersion", review.version() + 1);
        payload.put("publicationStatus", content.publicationStatus()); payload.put("publishedRevisionId", content.publishedRevisionId()); payload.put("occurredAt", now);
        if (writes.insertEvent(id(), contentId, review.revisionId(), reviewId, content.version() + 1, json.writeValueAsString(payload), timestamp) != 1) throw unavailable();
    }

    private String authorize(SessionPrincipal principal) {
        var account = accounts.findByIdForUpdate(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (!account.active()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.emailVerified()) || !accounts.findRolesForShare(account.id()).contains(UserRole.USER)) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        return account.id();
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "审核撤销数据服务暂不可用"); }
}
