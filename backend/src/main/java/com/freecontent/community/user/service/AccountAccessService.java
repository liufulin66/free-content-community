package com.freecontent.community.user.service;

import com.freecontent.community.user.model.AccountSnapshot;
import com.freecontent.community.user.model.UserRole;
import java.util.List;

/** 加锁方法必须在调用方事务中执行；业务权限判断仍由所属模块负责。 */
public interface AccountAccessService {
    AccountSnapshot findByIdForShare(String userId);
    AccountSnapshot findByIdForUpdate(String userId);
    List<UserRole> findRolesForShare(String userId);
}
