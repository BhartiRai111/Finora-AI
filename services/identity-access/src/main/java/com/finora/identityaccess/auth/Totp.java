package com.finora.identityaccess.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30 s) with RFC 4648 base32 for authenticator apps. */
final class Totp {

    static final int PERIOD_SECONDS = 30;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    static byte[] newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return secret;
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static long stepAt(long epochSeconds) {
        return epochSeconds / PERIOD_SECONDS;
    }

    /** @return the matching time step within +/-1 step of now, or -1. Compared in constant time. */
    static long match(byte[] secret, String submitted, long epochSeconds) {
        if (submitted == null || !submitted.matches("\\d{6}")) {
            return -1;
        }
        long now = stepAt(epochSeconds);
        long matched = -1;
        for (long step = now - 1; step <= now + 1; step++) {
            if (MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII),
                    submitted.getBytes(StandardCharsets.US_ASCII))) {
                matched = step;
            }
        }
        return matched;
    }
}
