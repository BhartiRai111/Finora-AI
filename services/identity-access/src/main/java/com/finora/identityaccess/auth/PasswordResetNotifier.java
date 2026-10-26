package com.finora.identityaccess.auth;

import java.time.Instant;

/**
 * Delivers a password-reset token to its owner. No email provider is approved or configured, so the shipped
 * implementation reports that delivery is unavailable; a provider is plugged in by supplying another bean.
 */
public interface PasswordResetNotifier {

    enum Outcome { SENT, NOT_CONFIGURED, FAILED }

    /** The implementation must never log the token. */
    Outcome deliver(String email, String token, Instant expiresAt);
}
