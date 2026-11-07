package com.freecontent.community.media.service.impl;

import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.media.dto.MediaTaskView;
import com.freecontent.community.media.ratelimit.MediaTaskQueryRateLimiter;
import com.freecontent.community.media.service.MediaTaskQueryService;
import com.freecontent.community.media.service.transaction.MediaTaskQueryTransactionService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.util.List;

@Service
public class MediaTaskQueryServiceImpl implements MediaTaskQueryService {
    private final MediaTaskQueryTransactionService transactions;
    private final MediaTaskQueryRateLimiter limiter;
    public MediaTaskQueryServiceImpl(MediaTaskQueryTransactionService transactions, MediaTaskQueryRateLimiter limiter) {
        this.transactions = transactions; this.limiter = limiter;
    }
    @Override
    public MediaTaskView getMyTask(String taskId, SessionPrincipal principal, String sourceIp) {
        if (principal == null || principal.user() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (taskId == null || !taskId.matches("^[A-Za-z0-9_-]{1,64}$")) throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                "任务ID格式不正确", null, null, List.of(new ProblemDetailResponse.Violation("path", "taskId", "仅允许1到64位字母、数字、下划线及短横线")));
        limiter.acquire(principal.user().id(), sourceIp);
        try { return transactions.read(taskId, principal); }
        catch (DataAccessException | TransactionException ex) { throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "媒体任务查询数据服务暂不可用"); }
    }
}
