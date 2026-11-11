package com.wannaverse.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Service
public class EncryptionService {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom;
    private final byte[] derivationKey;

    /**
     * Keys earlier versions encrypted with when nobody set ENCRYPTION_KEY: the value in the 1.x
     * docker-compose.yml and README, and the 1.x built-in default. Values written with them are
     * still read, so upgrading with the new compose file (which no longer sets the key) keeps
     * registry, git and SMTP credentials working. New values always use the current key.
     */
    private final List<SecretKey> previousKeys;

    public EncryptionService(@Value("${app.encryption.key}") String encryptionKey) {
        this.derivationKey = encryptionKey.getBytes(StandardCharsets.UTF_8).clone();
        this.secretKey = aesKey(encryptionKey);
        this.secureRandom = new SecureRandom();
        List<SecretKey> previous = new java.util.ArrayList<>();
        for (String old :
                List.of(
                        com.wannaverse.config.SecretsBootstrap.PUBLISHED_ENCRYPTION_KEY,
                        com.wannaverse.config.SecretsBootstrap.LEGACY_DEFAULT_ENCRYPTION_KEY)) {
            if (!old.equals(encryptionKey)) previous.add(aesKey(old));
        }
        this.previousKeys = List.copyOf(previous);
    }

    /** The AES key 1.x derived from a key string: its UTF-8 bytes, zero-padded or cut to 32. */
    private static SecretKey aesKey(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] fixed = new byte[32];
        System.arraycopy(keyBytes, 0, fixed, 0, Math.min(32, keyBytes.length));
        return new SecretKeySpec(fixed, "AES");
    }

    /**
     * A stable secret for one purpose (hex HMAC-SHA256 of {@code purpose} under the encryption
     * key). The same purpose always gives the same value, and it reveals nothing about the key.
     */
    public String deriveKey(String purpose) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derivationKey, "HmacSHA256"));
            return java.util.HexFormat.of()
                    .formatHex(mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return null;
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return null;
        }

        try {
            byte[] combined = Base64.getDecoder().decode(encryptedText);

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);

            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);

            // GCM's authentication tag tells which key was used: a wrong key always fails.
            Exception first = null;
            for (SecretKey key : candidates()) {
                try {
                    Cipher cipher = Cipher.getInstance(ALGORITHM);
                    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
                    return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
                } catch (javax.crypto.AEADBadTagException e) {
                    if (first == null) first = e;
                }
            }
            throw new RuntimeException(
                    "Decryption failed: this value was encrypted with a different ENCRYPTION_KEY",
                    first);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    private List<SecretKey> candidates() {
        List<SecretKey> keys = new java.util.ArrayList<>(previousKeys.size() + 1);
        keys.add(secretKey);
        keys.addAll(previousKeys);
        return keys;
    }

    public String generateWebhookSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
