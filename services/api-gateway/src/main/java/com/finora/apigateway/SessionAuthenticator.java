package com.finora.apigateway;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Seam to the session authority. The real implementation (validating the session cookie against identity-access) is
 * part of the identity work that has not been approved or built yet. Until a bean replaces the default, the gateway
 * fails closed: nobody is authenticated.
 */
public interface SessionAuthenticator {

    Optional<SessionIdentity> authenticate(HttpServletRequest request);
}
