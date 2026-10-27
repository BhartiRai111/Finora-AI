package com.finora.finance.business;

import com.finora.commons.authz.Action;
import com.finora.commons.authz.Grant;
import com.finora.commons.authz.PermissionMatrix;
import com.finora.commons.authz.Resource;
import com.finora.commons.authz.Role;
import com.finora.commons.authz.Scope;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.commons.security.InternalPrincipal;
import com.finora.commons.security.PrincipalKind;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Who is calling and for which business workspace, established only from the verified internal token (never from the
 * request body, query or headers). Permissions come from the shared {@link PermissionMatrix}; object-level scopes that this
 * service cannot evaluate (ASSIGNED, IF_GRANTED, LIMITED: there is no assignment or viewer-grant model yet) fail closed.
 */
public record BusinessContext(UUID tenantId, UUID userId, Role role, ZoneId zone, Clock clock) {

    public static final String BUSINESS_SCOPE = "workspace:business";

    /** Today's calendar date in the business's time zone, not the server's. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    /** Full-workspace access to the action, or {@link ApiException} 403. */
    public void requireAll(Resource resource, Action action) {
        if (scope(resource, action) != Scope.ALL) {
            throw new ApiException(ErrorCode.FORBIDDEN, null);
        }
    }

    /** The matrix scope for the action: ALL, or OWN (the caller may touch only records they created). Anything else is denied. */
    public Scope scope(Resource resource, Action action) {
        Grant grant = PermissionMatrix.check(role, resource, action);
        if (!grant.allowed() || (grant.scope() != Scope.ALL && grant.scope() != Scope.OWN)) {
            throw new ApiException(ErrorCode.FORBIDDEN, null);
        }
        return grant.scope();
    }

    /** Summaries: ALL, or the restricted summary view granted to Admins. Both are aggregate-only. */
    public void requireReports() {
        Grant grant = PermissionMatrix.check(role, Resource.REPORTS, Action.READ);
        if (!grant.allowed() || (grant.scope() != Scope.ALL && grant.scope() != Scope.LIMITED)) {
            throw new ApiException(ErrorCode.FORBIDDEN, null);
        }
    }

    static BusinessContext from(InternalPrincipal p, UUID pathSpace, ZoneId zone, Clock clock) {
        if (p == null || p.kind() != PrincipalKind.USER) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED, null);
        }
        if (p.tenantId() == null || !p.tenantId().equals(pathSpace)) {
            throw new ApiException(ErrorCode.NOT_FOUND, null);
        }
        if (!p.scopes().contains(BUSINESS_SCOPE)) {
            throw new ApiException(ErrorCode.CONFLICT, "Business finance is available in business workspaces only");
        }
        Role role = Role.parse(p.role()).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, null));
        return new BusinessContext(p.tenantId(), UUID.fromString(p.subject()), role, zone, clock);
    }
}
