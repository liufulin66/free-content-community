package com.freecontent.community.content.review.service.transaction;

import com.freecontent.community.content.review.dto.AdminReviewDetail;
import com.freecontent.community.content.review.dto.ReviewPage;
import com.freecontent.community.content.review.dto.ReviewView;
import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.content.model.persistence.ReviewRow;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.review.mapper.AdminReviewQueryMapper;
import com.freecontent.community.content.service.read.ContentWorkspaceReader;
import com.freecontent.community.user.model.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigInteger;
import java.time.*;
import java.util.List;

/** 共享锁保护当前后台资格，列表和详情分别在单次只读快照内完成。 */
@Service
public class AdminReviewQueryTransactionService {
    private final AdminReviewQueryMapper reviews;
    private final AccountAccessService accounts;
    private final ContentWorkspaceReader reader;
    public AdminReviewQueryTransactionService(AdminReviewQueryMapper reviews, AccountAccessService accounts, ContentWorkspaceReader reader) {
        this.reviews = reviews; this.accounts = accounts; this.reader = reader;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReviewPage list(BigInteger page, int pageSize, String state, String purpose, SessionPrincipal principal) {
        authorize(principal);
        long total = reviews.count(state, purpose);
        BigInteger offset = page.subtract(BigInteger.ONE).multiply(BigInteger.valueOf(pageSize));
        if (offset.compareTo(BigInteger.valueOf(total)) >= 0) return new ReviewPage(List.of(), page, pageSize, total);
        var rows = reviews.page(state, purpose, offset.longValueExact(), pageSize);
        return new ReviewPage(rows.stream().map(this::view).toList(), page, pageSize, total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminReviewDetail read(String reviewId, SessionPrincipal principal) {
        authorize(principal);
        var review = reviews.findById(reviewId);
        if (review == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var content = reader.findContent(review.contentId());
        if (content == null) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "审核关联的内容暂不可用");
        var frozen = reader.frozenRevision(content.id(), review.revisionId());
        return new AdminReviewDetail(view(review), reader.workspace(content), frozen);
    }

    private ReviewView view(ReviewRow row) {
        return new ReviewView(row.id(), row.contentId(), row.revisionId(), row.purpose(), row.state(), row.version(),
                instant(row.submittedAt()), instant(row.decidedAt()), row.reviewerId(), row.reason());
    }
    private Instant instant(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }
    private void authorize(SessionPrincipal principal) {
        var account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (account.disabled()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        var currentRoles = accounts.findRolesForShare(account.id());
        if (!Boolean.TRUE.equals(account.emailVerified()) || currentRoles.stream().noneMatch(role -> role == UserRole.MODERATOR || role == UserRole.ADMIN))
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要审核员或管理员的review:read权限");
    }
}
