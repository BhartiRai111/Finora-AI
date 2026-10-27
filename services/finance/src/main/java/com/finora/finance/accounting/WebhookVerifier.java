package com.finora.finance.accounting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Generic HMAC-SHA256 webhook check: the signature is the hex HMAC of {@code timestamp + "." + body} with the shared secret, compared in constant
 * time, and the timestamp must be within the tolerance of now (limits replay). A real provider may sign differently; its adapter then supplies its
 * own scheme. No webhook route is exposed today because no provider is approved.
 */
public final class WebhookVerifier {

    private WebhookVerifier() {
    }

    public static String sign(byte[] secret, long timestampSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestampSeconds + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable");
        }
    }

    public static boolean verify(byte[] secret, String timestampHeader, byte[] body, String signatureHex, Instant now, Duration tolerance) {
        if (secret == null || secret.length == 0 || timestampHeader == null || signatureHex == null || body == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - ts) > tolerance.getSeconds()) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, ts, body).getBytes(StandardCharsets.UTF_8), signatureHex.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }
}
