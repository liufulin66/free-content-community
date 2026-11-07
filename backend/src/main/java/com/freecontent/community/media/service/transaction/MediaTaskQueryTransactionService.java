package com.freecontent.community.media.service.transaction;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.dto.MediaTaskView;
import com.freecontent.community.media.mapper.MediaProcessingTaskMapper;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaTaskQueryTransactionService {
    private final UserAccountMapper users;
    private final MediaProcessingTaskMapper tasks;
    public MediaTaskQueryTransactionService(UserAccountMapper users, MediaProcessingTaskMapper tasks) {
        this.users = users; this.tasks = tasks;
    }
    @Transactional
    public MediaTaskView read(String taskId, SessionPrincipal principal) {
        UserAccount account = users.findByIdForShare(principal.user().id());
        if (account == null || account.getAuthVersion() == null || account.getAuthVersion() != principal.authVersion()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (account.getAccountStatus() == UserAccount.Status.DISABLED) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(account.getEmailVerified())) throw new BusinessException(ErrorCode.PERMISSION_DENIED, "当前账号不具备媒体任务查询权限");
        var task = tasks.findOwned(taskId, account.getId());
        if (task == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return MediaTaskView.from(task);
    }
}
