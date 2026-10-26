package com.finora.commons.security;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Readiness: a service that cannot authenticate any caller must not receive traffic. Exposes no details. */
public class InternalTokenHealthIndicator implements HealthIndicator {

    private final InternalTokenVerifier verifier;

    public InternalTokenHealthIndicator(InternalTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Health health() {
        return verifier.hasTrustedIssuers() ? Health.up().build() : Health.down().build();
    }
}
