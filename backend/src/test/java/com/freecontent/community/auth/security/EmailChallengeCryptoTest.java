package com.freecontent.community.auth.security;

import com.freecontent.community.auth.AuthTestSupport;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmailChallengeCryptoTest {

    private final EmailChallengeCrypto crypto = new EmailChallengeCrypto(AuthTestSupport.properties());

    @Test
    void digestIsBoundToChallengeEmailAndPurpose() {
        byte[] digest = crypto.digest("challenge1", "a@example.test",
                EmailChallengePurpose.REGISTER, "000123");
        assertEquals(32, digest.length);
        assertTrue(crypto.matches("v1", digest, "challenge1", "a@example.test",
                EmailChallengePurpose.REGISTER, "000123"));
        assertFalse(crypto.matches("v1", digest, "challenge2", "a@example.test",
                EmailChallengePurpose.REGISTER, "000123"));
        assertFalse(crypto.matches("v1", digest, "challenge1", "b@example.test",
                EmailChallengePurpose.REGISTER, "000123"));
        assertFalse(crypto.matches("v1", digest, "challenge1", "a@example.test",
                EmailChallengePurpose.PASSWORD_RESET, "000123"));
        assertFalse(crypto.matches("old-key", digest, "challenge1", "a@example.test",
                EmailChallengePurpose.REGISTER, "000123"));
    }

    @Test
    void encryptedCodeSurvivesRoundTripAndKeepsLeadingZeros() {
        var encrypted = crypto.encrypt("task1", "challenge1", "000123");
        assertEquals("000123", crypto.decrypt(encrypted.keyId(), "task1", "challenge1",
                encrypted.ciphertext(), encrypted.iv()));
    }

    @Test
    void rejectsCiphertextTamperingAndMovingToAnotherTask() {
        var encrypted = crypto.encrypt("task1", "challenge1", "123456");
        assertThrows(IllegalArgumentException.class, () ->
                crypto.decrypt(encrypted.keyId(), "task2", "challenge1",
                        encrypted.ciphertext(), encrypted.iv()));
        byte[] modified = encrypted.ciphertext().clone();
        modified[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () ->
                crypto.decrypt(encrypted.keyId(), "task1", "challenge1", modified, encrypted.iv()));
    }
}
