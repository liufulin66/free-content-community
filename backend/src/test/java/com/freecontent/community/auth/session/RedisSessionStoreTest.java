package com.freecontent.community.auth.session;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.security.TokenDigest;
import com.freecontent.community.common.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisSessionStoreTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisSessionStore store = new RedisSessionStore(redis,
            LoginTestSupport.properties("test:store:"), Clock.systemUTC());

    @Test
    void malformedTokenDoesNotReachRedis() {
        assertNull(store.find("bad-token"));
        assertNull(store.find(null));
        verifyNoInteractions(redis);
    }

    @Test
    void redisFailureIs503ForSessionOperations() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("internal connection detail"));
        assertUnavailable(() -> store.create("user1", 1, null));
        assertUnavailable(() -> store.find("a".repeat(43)));
        assertUnavailable(() -> store.revoke("user1", "session1", TokenDigest.sha256("token")));
        assertUnavailable(() -> store.revokeBeforeVersion("user1", 2));
    }

    @Test
    void unconfirmedRedisResultIsNotTreatedAsSuccessOrInvalidToken() {
        assertUnavailable(() -> store.create("user1", 1, null));
        assertUnavailable(() -> store.find("a".repeat(43)));
        assertUnavailable(() -> store.revoke("user1", "session1", TokenDigest.sha256("token")));
        assertUnavailable(() -> store.revokeBeforeVersion("user1", 2));
    }

    @Test
    void invalidRevocationIdentityCannotReachRedis() {
        for (String[] identity : new String[][] {
                {"other:{user}", "session1", TokenDigest.sha256("token")},
                {"user1", null, TokenDigest.sha256("token")},
                {"user1", "session1", "raw-token"}}) {
            assertEquals(ErrorCode.AUTHENTICATION_REQUIRED, assertThrows(BusinessException.class,
                    () -> store.revoke(identity[0], identity[1], identity[2])).errorCode());
        }
        verifyNoInteractions(redis);
    }

    @Test
    void ownershipMismatchIsRejected() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(-1L);
        assertEquals(ErrorCode.AUTHENTICATION_REQUIRED, assertThrows(BusinessException.class,
                () -> store.revoke("user1", "session1", TokenDigest.sha256("token"))).errorCode());
    }

    @Test
    void unexpectedScriptResultCannotConfirmRevocation() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(2L);
        assertUnavailable(() -> store.revoke("user1", "session1", TokenDigest.sha256("token")));
    }

    private void assertUnavailable(Runnable action) {
        BusinessException ex = assertThrows(BusinessException.class, action::run);
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, ex.errorCode());
        assertFalse(ex.getMessage().contains("internal connection detail"));
    }
}
