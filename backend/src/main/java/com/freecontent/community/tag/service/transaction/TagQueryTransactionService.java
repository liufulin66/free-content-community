package com.freecontent.community.tag.service.transaction;

import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.tag.dto.TagView;
import com.freecontent.community.tag.dto.TagPageView;
import com.freecontent.community.tag.dto.TagListView;
import com.freecontent.community.tag.model.TagStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.freecontent.community.tag.entity.TagEntity;
import com.freecontent.community.tag.mapper.TagMapper;
import com.freecontent.community.user.model.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigInteger;
import java.util.List;

/** 后台查询以账号、角色共享锁保护授权；公开查询仅过滤有效标签，所有查询均不修改标签。 */
@Service
public class TagQueryTransactionService {
    private final AccountAccessService accounts;
    private final TagMapper tags;

    public TagQueryTransactionService(AccountAccessService accounts, TagMapper tags) {
        this.accounts = accounts;
        this.tags = tags;
    }

    @Transactional(readOnly = true)
    public TagListView activeTags() {
        var rows = tags.selectList(Wrappers.<TagEntity>lambdaQuery().eq(TagEntity::getStatus, TagStatus.ACTIVE)
                .orderByAsc(TagEntity::getName).orderByAsc(TagEntity::getId));
        return new TagListView(rows.stream().map(this::toView).toList());
    }

    @Transactional
    public TagView read(String tagId, SessionPrincipal principal) {
        authorize(principal);
        var tag = tags.selectById(tagId);
        if (tag == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return toView(tag);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public TagPageView list(BigInteger page, int pageSize, SessionPrincipal principal) {
        authorize(principal);
        long total = tags.selectCount(null);
        BigInteger offset = page.subtract(BigInteger.ONE).multiply(BigInteger.valueOf(pageSize));
        if (offset.compareTo(BigInteger.valueOf(total)) >= 0) {
            return new TagPageView(List.of(), page, pageSize, total);
        }
        var pagination = new Page<TagEntity>(page.longValueExact(), pageSize, false);
        var rows = tags.selectPage(pagination, Wrappers.<TagEntity>lambdaQuery()
                .orderByDesc(TagEntity::getCreatedAt).orderByDesc(TagEntity::getId)).getRecords();
        var items = rows.stream().map(this::toView).toList();
        return new TagPageView(items, page, pageSize, total);
    }

    private TagView toView(TagEntity tag) {
        return new TagView(tag.getId(), tag.getName(), tag.getDescription(), tag.getStatus().name(), tag.getVersion());
    }

    private void authorize(SessionPrincipal principal) {
        AccountSnapshot account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.disabled()) {
            throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        }
        if (!Boolean.TRUE.equals(account.emailVerified()) || !accounts.findRolesForShare(account.id()).contains(UserRole.ADMIN)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要管理员的tag:manage权限");
        }
    }
}
