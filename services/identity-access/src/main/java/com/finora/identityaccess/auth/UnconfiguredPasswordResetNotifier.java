package com.finora.identityaccess.auth;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default: there is no email provider, so nothing is sent and nothing pretends otherwise. */
public class UnconfiguredPasswordResetNotifier implements PasswordResetNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(UnconfiguredPasswordResetNotifier.class);

    public UnconfiguredPasswordResetNotifier() {
        LOG.warn("Password-reset email delivery is NOT configured: reset tokens are created but cannot be delivered");
    }

    @Override
    public Outcome deliver(String email, String token, Instant expiresAt) {
        LOG.warn("Password reset requested but no email provider is configured; nothing was sent");
        return Outcome.NOT_CONFIGURED;
    }
}
