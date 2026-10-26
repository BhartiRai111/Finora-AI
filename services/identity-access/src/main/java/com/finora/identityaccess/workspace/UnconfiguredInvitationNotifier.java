package com.finora.identityaccess.workspace;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default: no email provider exists, so nothing is sent and nothing pretends otherwise. */
public class UnconfiguredInvitationNotifier implements InvitationNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(UnconfiguredInvitationNotifier.class);

    public UnconfiguredInvitationNotifier() {
        LOG.warn("Invitation email delivery is NOT configured: invitations are created but cannot be delivered");
    }

    @Override
    public Outcome deliver(String email, String workspaceName, String role, String acceptLink, Instant expiresAt) {
        LOG.warn("Invitation created but no email provider is configured; nothing was sent");
        return Outcome.NOT_CONFIGURED;
    }
}
