package com.finora.finance.accounting;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts provider tokens at rest with AES-256-GCM. The workspace and provider are bound into the ciphertext (authenticated data), so a stored
 * token copied to another workspace's row fails to open. Plaintext tokens exist only in memory; nothing returns them and the vault never
 * prints them ({@link #toString()} shows only availability).
 */
public final class TokenVault {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "v1:";
    private final byte[] key;

    TokenVault(byte[] key) {
        this.key = key == null ? null : key.clone();
    }

    public boolean available() {
        return key != null;
    }

    private static byte[] aad(java.util.UUID tenant, String provider) {
        return (tenant + "|" + provider).getBytes(StandardCharsets.UTF_8);
    }

    public String seal(java.util.UUID tenant, String provider, String plaintext) {
        if (key == null) {
            throw new IllegalStateException("The token vault is not configured");
        }
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(aad(tenant, provider));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The token could not be sealed");
        }
    }

    public String open(java.util.UUID tenant, String provider, String sealed) {
        if (key == null) {
            throw new IllegalStateException("The token vault is not configured");
        }
        try {
            if (sealed == null || !sealed.startsWith(PREFIX)) {
                throw new IllegalArgumentException("The stored token is not valid");
            }
            byte[] all = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, all, 0, 12));
            c.updateAAD(aad(tenant, provider));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("The stored token is not valid");
        }
    }

    @Override
    public String toString() {
        return "TokenVault[" + (available() ? "available" : "unavailable") + "]";
    }
}
