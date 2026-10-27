package com.finora.identityaccess.workspace;

import java.time.Instant;

/**
 * Delivers an invitation to its recipient. Provider-neutral: no email provider is approved or configured, so the
 * shipped default delivers nothing and says so. Implementations must never log the acceptance link.
 */
public interface InvitationNotifier {

    enum Outcome {
        /** Handed to a real provider. */
        SENT,
        /** No provider configured: nothing was sent. */
        NOT_CONFIGURED,
        /** Written to the development outbox file: nothing was emailed. */
        DEV_CAPTURED,
        FAILED
    }

    Outcome deliver(String email, String workspaceName, String role, String acceptLink, Instant expiresAt);
}
