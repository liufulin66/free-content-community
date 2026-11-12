package com.freecontent.community.content.workspace.service.transaction;

import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.ContentWorkspace;
import com.freecontent.community.content.workspace.dto.WorkspacePage;
import com.freecontent.community.content.mapper.ContentReadMapper;
import com.freecontent.community.content.service.read.ContentWorkspaceReader;
import com.freecontent.community.user.model.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigInteger;
import java.util.List;

/** 账号与角色共享锁保护授权；详情和分页共用投影检查，列表按页批量读取修订及引用。 */
@Service
public class ContentWorkspaceTransactionService {
    private final ContentReadMapper contents;
    private final AccountAccessService accounts;
    private final ContentWorkspaceReader reader;
    public ContentWorkspaceTransactionService(ContentReadMapper contents, AccountAccessService accounts, ContentWorkspaceReader reader) {
        this.contents = contents;
        this.accounts = accounts;
        this.reader = reader;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContentWorkspace read(String contentId, SessionPrincipal principal) {
        authorize(principal);
        var content = contents.findOwned(contentId, principal.user().id());
        if (content == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return reader.workspace(content);
    }

    @Transactional(readOnly = true,isolation = Isolation.REPEATABLE_READ)
    public WorkspacePage list(BigInteger page,int pageSize,String lifecycle,String publicationStatus,String kind,SessionPrincipal principal) {
        authorize(principal);
        String owner = principal.user().id();
        long total = contents.countOwned(owner,lifecycle,publicationStatus,kind);
        BigInteger offset = page.subtract(BigInteger.ONE).multiply(BigInteger.valueOf(pageSize));
        if (offset.compareTo(BigInteger.valueOf(total)) >= 0) return new WorkspacePage(List.of(),page,pageSize,total);
        var rows = contents.pageOwned(owner,lifecycle,publicationStatus,kind,offset.longValueExact(),pageSize);
        return new WorkspacePage(reader.workspaces(rows),page,pageSize,total);
    }

    private void authorize(SessionPrincipal principal) {
        AccountSnapshot account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.disabled()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.emailVerified()) || !accounts.findRolesForShare(account.id()).contains(UserRole.USER)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "当前账号不具备工作区读取权限");
        }
    }
}
