package com.freecontent.community.media.service.transaction;

import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.dto.MediaTaskView;
import com.freecontent.community.media.mapper.MediaProcessingTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaTaskQueryTransactionService {
    private final AccountAccessService accounts;
    private final MediaProcessingTaskMapper tasks;
    public MediaTaskQueryTransactionService(AccountAccessService accounts, MediaProcessingTaskMapper tasks) {
        this.accounts = accounts; this.tasks = tasks;
    }
    @Transactional
    public MediaTaskView read(String taskId, SessionPrincipal principal) {
        AccountSnapshot account = accounts.findByIdForShare(principal.user().id());
        if (account == null || account.authVersion() == null || account.authVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.disabled()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.emailVerified())) throw new BusinessException(ErrorCode.PERMISSION_DENIED, "当前账号不具备媒体任务查询权限");
        var task = tasks.findOwned(taskId, account.id());
        if (task == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return MediaTaskView.from(task);
    }
}
