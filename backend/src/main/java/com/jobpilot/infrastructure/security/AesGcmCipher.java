package com.jobpilot.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * API Key 的 AES-GCM 加解密与展示掩码（FRX.10）。
 *
 * <p>密文格式：{@code Base64(IV(12B) || ciphertext+tag)}，IV 每次随机。
 * 主密钥只来自环境变量 {@code APP_SECRET_KEY}：优先按 Base64 解码（16/24/32 字节直接作为 AES 密钥），
 * 否则对原文做 SHA-256 派生 32 字节密钥。密钥缺失时启动即失败，避免用空密钥"假加密"。
 */
@Component
public class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public AesGcmCipher(@Value("${app.security.secret-key:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("APP_SECRET_KEY 未配置，无法加密 API Key（见 .env.example）");
        }
        this.key = new SecretKeySpec(deriveKey(secret), "AES");
    }

    /** 加密并返回 Base64 密文。 */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception ex) {
            throw new IllegalStateException("API Key 加密失败", ex);
        }
    }

    /** 解密密文；密文被篡改或密钥不匹配时抛出 {@link IllegalStateException}。 */
    public String decrypt(String ciphertext) {
        try {
            byte[] raw = Base64.getDecoder().decode(ciphertext);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("API Key 解密失败", ex);
        }
    }

    /** 展示掩码：保留前缀 3 位与末 4 位，中间固定 4 个星号；过短密钥全部隐藏。 */
    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        return apiKey.length() <= 8 ? "****"
                : apiKey.substring(0, 3) + "****" + apiKey.substring(apiKey.length() - 4);
    }

    private static byte[] deriveKey(String secret) {
        try {
            byte[] decoded = Base64.getDecoder().decode(secret);
            if (decoded.length == 16 || decoded.length == 24 || decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // 非 Base64 文本，走 SHA-256 派生
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("无法派生加密密钥", ex);
        }
    }
}
