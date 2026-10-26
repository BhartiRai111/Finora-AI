package com.finora.apigateway;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Default until identity-access provides session validation: no request is ever treated as authenticated. */
public class FailClosedSessionAuthenticator implements SessionAuthenticator {

    @Override
    public Optional<SessionIdentity> authenticate(HttpServletRequest request) {
        return Optional.empty();
    }
}
