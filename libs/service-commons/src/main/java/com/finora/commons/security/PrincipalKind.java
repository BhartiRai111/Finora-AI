package com.finora.commons.security;

/** Who an internal token speaks for. */
public enum PrincipalKind {
    /** An authenticated end user, asserted by the gateway. */
    USER,
    /** No end user (public routes), asserted by the gateway only. */
    ANONYMOUS,
    /** A service acting under its own workload identity. */
    SERVICE
}
