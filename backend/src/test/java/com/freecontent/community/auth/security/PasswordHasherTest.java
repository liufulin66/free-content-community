package com.freecontent.community.auth.security;

import com.freecontent.community.auth.AuthTestSupport;
import com.freecontent.community.auth.config.RegistrationConfiguration;
import com.freecontent.community.auth.config.RegistrationProperties;
import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PasswordHasherTest {
    @Test
    void usesSaltedArgon2AndPreservesWhitespace() {
        var properties = AuthTestSupport.registrationProperties();
        PasswordEncoder encoder = new RegistrationConfiguration().passwordEncoder(properties);
        PasswordHasher hasher = new PasswordHasher(encoder, properties);
        String password = "  RegistrationPassword!  ";
        String first = hasher.encode(password);
        String second = hasher.encode(password);
        assertTrue(first.startsWith("{argon2id}$argon2id$"));
        assertNotEquals(first, second);
        assertTrue(encoder.matches(password, first));
        assertFalse(encoder.matches(password.trim(), first));
    }

    @Test
    void entireMaximumLengthUnicodePasswordParticipatesInHash() {
        var properties = AuthTestSupport.registrationProperties();
        PasswordEncoder encoder = new RegistrationConfiguration().passwordEncoder(properties);
        String password = "😀".repeat(127) + "甲";
        String hash = new PasswordHasher(encoder, properties).encode(password);
        assertTrue(encoder.matches(password, hash));
        assertFalse(encoder.matches("😀".repeat(127) + "乙", hash));
    }

    @Test
    void expensiveHashConcurrencyIsBoundedAndPermitsAreReleased() throws Exception {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        var properties = new RegistrationProperties(5, 30, 20, 19456, 2, 1, 1, "test:auth:");
        PasswordHasher hasher = new PasswordHasher(encoder, properties);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(encoder.encode(anyString())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test synchronization timed out");
            }
            return "hash";
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = executor.submit(() -> hasher.encode("RegistrationPassword!"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            BusinessException error = assertThrows(BusinessException.class,
                    () -> hasher.encode("RegistrationPassword!"));
            assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode());
            release.countDown();
            assertEquals("hash", first.get(5, TimeUnit.SECONDS));
            assertEquals("hash", hasher.encode("RegistrationPassword!"));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
