package com.freecontent.community.auth.security;

import com.freecontent.community.auth.config.EmailChallengeProperties;
import com.freecontent.community.auth.model.EmailChallengePurpose;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * 摘要绑定申请、邮箱和用途；密文绑定邮件任务和申请，防止跨记录替换。
 * 两类密钥必须稳定配置，不能在每次服务启动时随机重建。
 */
@Component
public class EmailChallengeCrypto {

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec macKey;
    private final SecretKeySpec encryptionKey;
    private final String macKeyId;
    private final String encryptionKeyId;

    public EmailChallengeCrypto(EmailChallengeProperties properties) {
        this.macKeyId = properties.macKeyId();
        this.encryptionKeyId = properties.encryptionKeyId();
        this.macKey = new SecretKeySpec(decodeKey(properties.macKeyBase64(), "HMAC"), "HmacSHA256");
        this.encryptionKey = new SecretKeySpec(decodeKey(properties.encryptionKeyBase64(), "AES"), "AES");
        if (MessageDigest.isEqual(macKey.getEncoded(), encryptionKey.getEncoded())) {
            throw new IllegalArgumentException("HMAC和AES必须使用不同的密钥");
        }
    }

    public String generateCode() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    public byte[] digest(String challengeId, String email, EmailChallengePurpose purpose, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKey);
            String value = challengeId + "\0" + email + "\0" + purpose.name() + "\0" + code;
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("验证码摘要计算失败", ex);
        }
    }

    public boolean matches(String keyId, byte[] expected, String challengeId,
                           String email, EmailChallengePurpose purpose, String code) {
        return macKeyId.equals(keyId) && expected != null
                && MessageDigest.isEqual(expected, digest(challengeId, email, purpose, code));
    }

    public EncryptedCode encrypt(String taskId, String challengeId, String code) {
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(taskId, challengeId));
            return new EncryptedCode(cipher.doFinal(code.getBytes(StandardCharsets.US_ASCII)),
                    iv, encryptionKeyId);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("验证码加密失败", ex);
        }
    }

    public String decrypt(String keyId, String taskId, String challengeId, byte[] ciphertext, byte[] iv) {
        if (!encryptionKeyId.equals(keyId) || ciphertext == null || iv == null || iv.length != 12) {
            throw new IllegalArgumentException("邮件验证码密文或密钥标识不可用");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(taskId, challengeId));
            String code = new String(cipher.doFinal(ciphertext), StandardCharsets.US_ASCII);
            if (!code.matches("[0-9]{6}")) {
                throw new IllegalArgumentException("邮件验证码内容不可用");
            }
            return code;
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("邮件验证码解密失败", ex);
        }
    }

    public String macKeyId() {
        return macKeyId;
    }

    private byte[] aad(String taskId, String challengeId) {
        return (taskId + "\0" + challengeId).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] decodeKey(String encoded, String type) {
        try {
            byte[] key = Base64.getDecoder().decode(encoded == null ? "" : encoded);
            if (key.length != 32) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(type + "密钥必须配置为32字节随机值的Base64编码");
        }
    }

    public record EncryptedCode(byte[] ciphertext, byte[] iv, String keyId) {
    }
}
