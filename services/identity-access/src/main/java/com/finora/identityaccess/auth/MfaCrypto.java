package com.finora.identityaccess.auth;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM for TOTP secrets at rest. The user id is bound as associated data so a blob cannot be moved between users. */
public class MfaCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key;

    /** @param key 32 bytes, or null when no key is configured (MFA then reports itself unavailable) */
    public MfaCrypto(byte[] key) {
        if (key != null && key.length != 32) {
            throw new IllegalStateException("MFA encryption key must be exactly 256 bits");
        }
        this.key = key == null ? null : key.clone();
    }

    public boolean available() {
        return key != null;
    }

    byte[] encrypt(UUID userId, byte[] plaintext) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(userId));
            byte[] ct = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MFA secret encryption failed", e);
        }
    }

    byte[] decrypt(UUID userId, byte[] blob) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, blob, 0, 12));
            cipher.updateAAD(aad(userId));
            return cipher.doFinal(blob, 12, blob.length - 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MFA secret could not be decrypted", e);
        }
    }

    private static byte[] aad(UUID userId) {
        return ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                .putLong(userId.getMostSignificantBits()).putLong(userId.getLeastSignificantBits()).array();
    }
}
