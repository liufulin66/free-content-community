package com.freecontent.community.tag.service.transaction;

import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.tag.dto.TagUpdateRequest;
import com.freecontent.community.tag.dto.TagView;
import com.freecontent.community.tag.mapper.TagMapper;
import com.freecontent.community.tag.entity.TagEntity;
import com.freecontent.community.user.model.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** 账号→角色→标签固定锁序；授权复核、标签变更与版本递增共享一次提交。 */
@Service
public class TagUpdateTransactionService {
    private final TagMapper tags;
    private final AccountAccessService accounts;
    private final Clock clock;

    public TagUpdateTransactionService(TagMapper tags, AccountAccessService accounts, Clock clock) {
        this.tags = tags;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public TagView update(String tagId, TagUpdateRequest patch, SessionPrincipal principal) {
        authorize(principal);
        var current = tags.findByIdForUpdate(tagId);
        if (current == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if (!Objects.equals(current.getVersion(), patch.getExpectedVersion())) throw conflict(current.getVersion());
        boolean changed = (patch.hasName() && !Objects.equals(patch.getName(), current.getName()))
                || (patch.hasDescription() && !Objects.equals(patch.getDescription(), current.getDescription()))
                || (patch.hasStatus() && patch.getStatus() != current.getStatus());
        if (!changed) return view(current);
        if (current.getVersion() == Integer.MAX_VALUE) throw conflict(current.getVersion());
        try {
            if (tags.updateFields(tagId, patch.getExpectedVersion(), patch, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)) != 1) {
                throw conflict(current.getVersion());
            }
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.TAG_NAME_CONFLICT, "标签名称已存在");
        }
        return view(tags.selectById(tagId));
    }

    private TagView view(TagEntity tag) {
        return new TagView(tag.getId(), tag.getName(), tag.getDescription(), tag.getStatus().name(), tag.getVersion());
    }

    private void authorize(SessionPrincipal principal) {
        AccountSnapshot account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (!account.active()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.emailVerified()) || !accounts.findRolesForShare(account.id()).contains(UserRole.ADMIN)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要管理员的tag:manage权限");
        }
    }

    private BusinessException conflict(int version) {
        return new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "标签已被修改，请重新读取后确认", version);
    }
}
