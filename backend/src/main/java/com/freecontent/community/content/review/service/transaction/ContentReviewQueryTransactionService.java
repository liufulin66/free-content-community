package com.freecontent.community.content.review.service.transaction;

import com.freecontent.community.content.review.dto.ReviewResult;
import com.freecontent.community.content.review.dto.ReviewView;
import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.review.mapper.ContentReviewQueryMapper;
import com.freecontent.community.user.model.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;

/** 共享锁复核账号及角色；业务数据使用只读快照，不推进审核或内容状态。 */
@Service
public class ContentReviewQueryTransactionService {
    private final ContentReviewQueryMapper reviews;
    private final AccountAccessService accounts;
    public ContentReviewQueryTransactionService(ContentReviewQueryMapper reviews, AccountAccessService accounts) {
        this.reviews = reviews; this.accounts = accounts; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReviewResult read(String contentId, String reviewId, SessionPrincipal principal) {
        String owner = authorize(principal);
        var row = reviews.findOwned(contentId, reviewId, owner);
        if (row == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return view(row);
    }

    private ReviewResult view(ContentReviewQueryMapper.OwnedReviewRow row) {
        var review = new ReviewView(row.id(), row.contentId(), row.revisionId(), row.purpose(), row.state(), row.version(),
                instant(row.submittedAt()), instant(row.decidedAt()), row.reviewerId(), row.reason());
        return new ReviewResult(review, row.contentVersion());
    }

    private String authorize(SessionPrincipal principal) {
        var account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion())
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (account.disabled()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.emailVerified()) || !accounts.findRolesForShare(account.id()).contains(UserRole.USER))
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "当前账号不具备审核查询权限");
        return account.id();
    }
    private Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
}
