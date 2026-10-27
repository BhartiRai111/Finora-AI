package com.finora.commons.authz;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Business-workspace roles (PRD §4.1). Approval authority is deliberately NOT a role. Platform staff roles live in the
 * separate admin plane and are not represented here.
 */
public enum Role {
    OWNER, ADMIN, ACCOUNTANT, MANAGER, CONTRIBUTOR, AUDITOR, VIEWER;

    /** Roles an Admin may grant, change or remove (DEC-15 default: "Owner any; Admin up to Accountant-level"). */
    private static final Set<Role> ADMIN_MANAGEABLE = EnumSet.of(ACCOUNTANT, MANAGER, CONTRIBUTOR, AUDITOR, VIEWER);

    /** Roles that must authenticate with MFA to act in a workspace (DEC-20 default: mandatory for Owner/Admin). */
    public boolean requiresMfa() {
        return this == OWNER || this == ADMIN;
    }

    /** May this role grant {@code target} (invite with it, or change someone to it)? */
    public boolean canGrant(Role target) {
        return switch (this) {
            case OWNER -> true;
            case ADMIN -> ADMIN_MANAGEABLE.contains(target);
            default -> false;
        };
    }

    /** May this role change or remove a member who currently holds {@code target}? */
    public boolean canManage(Role target) {
        return canGrant(target);
    }

    /** The roles for which MFA is mandatory. */
    public static Set<Role> requiresMfaFor() {
        return EnumSet.of(OWNER, ADMIN);
    }

    public static Optional<Role> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
