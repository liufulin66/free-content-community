package com.freecontent.community.auth.session;

import java.time.Instant;

/** 不含原始Token；角色和账号状态不存快照，每次认证读取MySQL。 */
public record StoredSession(String sessionId, String userId, long authVersion,
                            Instant createdAt, Instant expiresAt, String deviceLabel) { }
