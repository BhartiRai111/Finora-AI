package com.finora.identityaccess.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** RFC 6238 Appendix B vectors (SHA-1 secret "12345678901234567890"), truncated to the last 6 digits. */
class TotpTest {

    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesTheRfcTestVectors() {
        assertThat(Totp.code(SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(SECRET, 1111111111L / 30)).isEqualTo("050471");
        assertThat(Totp.code(SECRET, 1234567890L / 30)).isEqualTo("005924");
        assertThat(Totp.code(SECRET, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void acceptsOneStepOfClockDriftButNoMore() {
        long t = 1234567890L;
        String code = Totp.code(SECRET, t / 30);
        assertThat(Totp.match(SECRET, code, t)).isEqualTo(t / 30);
        assertThat(Totp.match(SECRET, code, t + 30)).isEqualTo(t / 30);
        assertThat(Totp.match(SECRET, code, t - 30)).isEqualTo(t / 30);
        assertThat(Totp.match(SECRET, code, t + 90)).isEqualTo(-1);
        assertThat(Totp.match(SECRET, "12345", t)).isEqualTo(-1);
        assertThat(Totp.match(SECRET, "abcdef", t)).isEqualTo(-1);
        assertThat(Totp.match(SECRET, null, t)).isEqualTo(-1);
    }

    @Test
    void base32UsesTheRfc4648Alphabet() {
        assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(Totp.base32(SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    }

    @Test
    void secretsAreEncryptedAtRestAndBoundToTheUser() {
        MfaCrypto crypto = new MfaCrypto(new byte[32]);
        java.util.UUID user = java.util.UUID.randomUUID();
        byte[] blob = crypto.encrypt(user, SECRET);
        assertThat(new String(blob, StandardCharsets.ISO_8859_1)).doesNotContain("12345678901234567890");
        assertThat(crypto.decrypt(user, blob)).isEqualTo(SECRET);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crypto.decrypt(java.util.UUID.randomUUID(), blob))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new MfaCrypto(null).available()).isFalse();
    }
}
