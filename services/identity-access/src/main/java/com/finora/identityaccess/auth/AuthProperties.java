package com.finora.identityaccess.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Authentication settings. Defaults are documented in docs/identity-auth.md; all are overridable from the environment. */
@ConfigurationProperties("finora.auth")
public class AuthProperties {

    private final Cookie cookie = new Cookie();
    private final Lockout lockout = new Lockout();
    private Duration idleTimeout = Duration.ofMinutes(30);
    private Duration absoluteTimeout = Duration.ofHours(12);
    private Duration resetTokenTtl = Duration.ofMinutes(30);
    private Duration resetRequestInterval = Duration.ofSeconds(60);
    private Duration touchInterval = Duration.ofSeconds(60);
    private Duration stepUpTtl = Duration.ofMinutes(10);
    private final Mfa mfa = new Mfa();

    public static class Cookie {
        /** Secure cookies (HTTPS). Only switch off for plain-HTTP local development. */
        private boolean secure = true;
        private String sameSite = "Lax";

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }

        public String getSameSite() {
            return sameSite;
        }

        public void setSameSite(String sameSite) {
            this.sameSite = sameSite;
        }
    }

    public static class Lockout {
        private int threshold = 5;
        private Duration baseDelay = Duration.ofSeconds(30);
        private Duration maxDelay = Duration.ofMinutes(15);
        private Duration window = Duration.ofHours(1);

        public int getThreshold() {
            return threshold;
        }

        public void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        public Duration getBaseDelay() {
            return baseDelay;
        }

        public void setBaseDelay(Duration baseDelay) {
            this.baseDelay = baseDelay;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }

    public static class Mfa {
        /** File holding a base64-encoded 256-bit key used to encrypt TOTP secrets. Without it MFA cannot be enrolled. */
        private String encryptionKeyPath;
        private String issuerName = "Finora AI";

        public String getEncryptionKeyPath() {
            return encryptionKeyPath;
        }

        public void setEncryptionKeyPath(String encryptionKeyPath) {
            this.encryptionKeyPath = encryptionKeyPath;
        }

        public String getIssuerName() {
            return issuerName;
        }

        public void setIssuerName(String issuerName) {
            this.issuerName = issuerName;
        }
    }

    public Mfa getMfa() {
        return mfa;
    }

    public Duration getStepUpTtl() {
        return stepUpTtl;
    }

    public void setStepUpTtl(Duration stepUpTtl) {
        this.stepUpTtl = stepUpTtl;
    }

    public String cookieName() {
        return cookie.secure ? "__Host-finora_sid" : "finora_sid";
    }

    public Cookie getCookie() {
        return cookie;
    }

    public Lockout getLockout() {
        return lockout;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public Duration getAbsoluteTimeout() {
        return absoluteTimeout;
    }

    public void setAbsoluteTimeout(Duration absoluteTimeout) {
        this.absoluteTimeout = absoluteTimeout;
    }

    public Duration getResetTokenTtl() {
        return resetTokenTtl;
    }

    public void setResetTokenTtl(Duration resetTokenTtl) {
        this.resetTokenTtl = resetTokenTtl;
    }

    public Duration getResetRequestInterval() {
        return resetRequestInterval;
    }

    public void setResetRequestInterval(Duration resetRequestInterval) {
        this.resetRequestInterval = resetRequestInterval;
    }

    public Duration getTouchInterval() {
        return touchInterval;
    }

    public void setTouchInterval(Duration touchInterval) {
        this.touchInterval = touchInterval;
    }
}
