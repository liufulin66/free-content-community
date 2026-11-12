package com.freecontent.community.tag.service.transaction;

import com.freecontent.community.user.service.AccountAccessService;
import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.auth.security.SessionPrincipal;
import com.freecontent.community.common.error.*;
import com.freecontent.community.tag.dto.TagView;
import com.freecontent.community.tag.entity.*;
import com.freecontent.community.tag.mapper.*;
import com.freecontent.community.tag.model.TagStatus;
import com.freecontent.community.user.model.UserRole;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.*;
import java.util.*;

@Service
public class TagCreationTransactionService {
    private final AccountAccessService accounts;
    private final TagMapper tags;
    private final TagCreationRequestMapper requests;
    private final Clock clock;
    public TagCreationTransactionService(AccountAccessService accounts, TagMapper tags,
                                         TagCreationRequestMapper requests, Clock clock) {
        this.accounts = accounts; this.tags = tags; this.requests = requests; this.clock = clock;
    }

    public record Input(String name, String description, TagStatus status) { }
    public record Claim(TagCreationRequestRecord request, Input input, TagView replay) { }

    // 缺失幂等键不取间隙锁；同一调用者仍由账号行锁串行化，跨调用者由唯一约束保证安全。
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Claim claim(SessionPrincipal principal, String keyHash, String fingerprint, Input input) {
        String actor = authorize(principal); LocalDateTime now = now();
        TagCreationRequestRecord record = requests.lockByKey(actor, keyHash);
        if (record != null && !record.getExpiresAt().isAfter(now)) { requests.deleteById(record.getId()); record = null; }
        if (record != null) {
            if (!Objects.equals(record.getFingerprint(), fingerprint)) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            if ("SUCCEEDED".equals(record.getState())) {
                if (tags.selectById(record.getTagId()) == null) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "标签创建结果暂不可用");
                return new Claim(record, input, new TagView(record.getTagId(), record.getResultName(), record.getResultDescription(),
                        record.getResultStatus().name(), record.getResultVersion()));
            }
            if (record.getLeaseUntil() != null && record.getLeaseUntil().isAfter(now)) {
                throw inProgress(Math.max(1, (Duration.between(now, record.getLeaseUntil()).toMillis() + 999) / 1000));
            }
            record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60));
            if (requests.renew(record.getId(), record.getLeaseToken(), record.getLeaseUntil()) != 1) throw inProgress(1);
        } else {
            record = new TagCreationRequestRecord(); record.setId(id()); record.setCreatedBy(actor);
            record.setKeyHash(keyHash); record.setFingerprint(fingerprint); record.setState("PROCESSING");
            record.setLeaseToken(id()); record.setLeaseUntil(now.plusSeconds(60));
            record.setCreatedAt(now); record.setExpiresAt(now.plusHours(24)); requests.insert(record);
        }
        return new Claim(record, input, null);
    }

    @Transactional
    public TagView finish(Claim claim, SessionPrincipal principal) {
        String actor = authorize(principal); LocalDateTime now = now();
        TagCreationRequestRecord record = requests.lockByKey(actor, claim.request().getKeyHash());
        if (record == null || !Objects.equals(record.getId(), claim.request().getId()) || !"PROCESSING".equals(record.getState())
                || !Objects.equals(record.getLeaseToken(), claim.request().getLeaseToken())
                || record.getLeaseUntil() == null || !record.getLeaseUntil().isAfter(now)) throw inProgress(1);
        var tag = new TagEntity(); tag.setId(id()); tag.setName(claim.input().name());
        tag.setDescription(claim.input().description()); tag.setStatus(claim.input().status()); tag.setVersion(1);
        tag.setCreatedBy(actor); tag.setCreatedAt(now); tag.setUpdatedAt(now);
        try { tags.insert(tag); }
        catch (DuplicateKeyException ex) { throw new BusinessException(ErrorCode.TAG_NAME_CONFLICT, "标签名称已存在"); }
        if (requests.complete(record.getId(), record.getLeaseToken(), tag, now()) != 1) throw inProgress(1);
        return new TagView(tag.getId(), tag.getName(), tag.getDescription(), tag.getStatus().name(), tag.getVersion());
    }

    @Transactional
    public void release(Claim claim) { requests.release(claim.request().getId(), claim.request().getLeaseToken()); }

    private String authorize(SessionPrincipal principal) {
        AccountSnapshot user = accounts.findByIdForUpdate(principal.user().id());
        if (user == null || user.authVersion() == null || user.authVersion() != principal.authVersion()) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (!user.active()) throw new BusinessException(ErrorCode.ACCOUNT_RESTRICTED);
        if (!Boolean.TRUE.equals(user.emailVerified()) || !accounts.findRolesForShare(user.id()).contains(UserRole.ADMIN)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "需要管理员的tag:manage权限");
        }
        return user.id();
    }
    private BusinessException inProgress(long seconds) { return new BusinessException(ErrorCode.REQUEST_IN_PROGRESS, "相同标签创建请求正在处理", null, seconds, List.of()); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
}
