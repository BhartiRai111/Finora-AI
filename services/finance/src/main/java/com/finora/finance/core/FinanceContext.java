package com.finora.finance.core;

import com.finora.commons.authz.Action;
import com.finora.commons.authz.PermissionMatrix;
import com.finora.commons.authz.Resource;
import com.finora.commons.authz.Role;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.commons.security.InternalPrincipal;
import com.finora.commons.security.PrincipalKind;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** Who is calling and for which personal profile, established only from the verified internal token. */
public record FinanceContext(UUID tenantId, UUID userId, Role role, ZoneId zone, Clock clock) {

    public static final String PERSONAL_SCOPE = "workspace:personal";

    /** Today's calendar date for this user (their time zone), not the server's. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    /**
     * Personal finance belongs to the personal profile's owner. The matrix is consulted for the action (default deny) and
     * the role must be Owner: nothing in this service is available to other roles, and business workspaces are refused.
     */
    public void require(Action action) {
        if (role != Role.OWNER || !PermissionMatrix.allows(role, Resource.TRANSACTIONS, action)) {
            throw new ApiException(ErrorCode.FORBIDDEN, null);
        }
    }

    /** The zero UUID marks changes made by the system itself (for example the recurring-transaction scheduler). */
    public static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

    public static FinanceContext system(UUID tenantId, ZoneId zone, Clock clock) {
        return new FinanceContext(tenantId, SYSTEM_ACTOR, Role.OWNER, zone, clock);
    }

    static FinanceContext from(InternalPrincipal p, UUID pathSpace, ZoneId zone, Clock clock) {
        if (p == null || p.kind() != PrincipalKind.USER) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED, null);
        }
        if (p.tenantId() == null || !p.tenantId().equals(pathSpace)) {
            throw new ApiException(ErrorCode.NOT_FOUND, null);
        }
        if (!p.scopes().contains(PERSONAL_SCOPE)) {
            throw new ApiException(ErrorCode.CONFLICT, "Finance is available in your personal profile only for now");
        }
        Role role = Role.parse(p.role()).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, null));
        return new FinanceContext(p.tenantId(), UUID.fromString(p.subject()), role, zone, clock);
    }
}
