package com.freecontent.community.user.service.impl;

import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.mapper.UserAccountMapper;
import com.freecontent.community.user.mapper.UserRoleMapper;
import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.AccountAccessService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AccountAccessServiceImpl implements AccountAccessService {
    private final UserAccountMapper accounts;
    private final UserRoleMapper roles;

    public AccountAccessServiceImpl(UserAccountMapper accounts, UserRoleMapper roles) {
        this.accounts = accounts;
        this.roles = roles;
    }

    @Override
    public AccountSnapshot findByIdForShare(String userId) {
        return snapshot(accounts.findByIdForShare(userId));
    }

    @Override
    public AccountSnapshot findByIdForUpdate(String userId) {
        return snapshot(accounts.findByIdForUpdate(userId));
    }

    @Override
    public List<UserRole> findRolesForShare(String userId) {
        return roles.findRolesForShare(userId);
    }

    private AccountSnapshot snapshot(UserAccount account) {
        return account == null ? null : new AccountSnapshot(account.getId(), account.getAuthVersion(),
                Boolean.TRUE.equals(account.getEmailVerified()), account.getAccountStatus() == UserAccount.Status.ACTIVE,
                account.getAccountStatus() == UserAccount.Status.DISABLED);
    }
}
