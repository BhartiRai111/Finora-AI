package com.finora.testkit;

import com.finora.commons.security.InternalTokenIssuer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Builds internal tokens for tests, signed by throw-away keys. */
public final class TestTokens {

    public static final String GATEWAY = "gateway";
    /** A service that the test configuration lists as a trusted issuer. */
    public static final String CALLER = "testcaller";

    private TestTokens() {
    }

    public static InternalTokenIssuer issuer(String name, Duration ttl, Clock clock) {
        return new InternalTokenIssuer(name, TestKeys.privateKey(name), ttl, clock);
    }

    public static InternalTokenIssuer issuer(String name) {
        return issuer(name, Duration.ofMinutes(2), Clock.systemUTC());
    }

    public static String service(String audience) {
        return issuer(CALLER).issueService(audience, null);
    }

    public static String gatewayUser(String audience, UUID tenant) {
        return issuer(GATEWAY).issueUser(audience, UUID.randomUUID(), UUID.randomUUID(), tenant,
                tenant == null ? null : UUID.randomUUID(), tenant == null ? null : "OWNER", List.of(), null);
    }

    public static String gatewayAnonymous(String audience) {
        return issuer(GATEWAY).issueAnonymous(audience);
    }

    public static Clock fixedAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
