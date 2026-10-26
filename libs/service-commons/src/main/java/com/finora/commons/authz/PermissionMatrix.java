package com.finora.commons.authz;

import static com.finora.commons.authz.Action.APPROVE;
import static com.finora.commons.authz.Action.ARCHIVE;
import static com.finora.commons.authz.Action.CREATE;
import static com.finora.commons.authz.Action.EXECUTE;
import static com.finora.commons.authz.Action.EXPORT;
import static com.finora.commons.authz.Action.ISSUE;
import static com.finora.commons.authz.Action.READ;
import static com.finora.commons.authz.Action.UNMASK;
import static com.finora.commons.authz.Action.UPDATE;
import static com.finora.commons.authz.Resource.*;
import static com.finora.commons.authz.Role.*;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The documented permission matrix (PRD §4.2) as code. Anything not listed is denied (PERM-1, default deny). A test
 * compares this table against the PRD text so the two cannot drift silently. Platform staff, approval assignments and
 * object-level scopes are outside this table by design.
 */
public final class PermissionMatrix {

    private static final Set<Action> NONE = EnumSet.noneOf(Action.class);
    private static final Map<Resource, Map<Role, Grant>> TABLE = new EnumMap<>(Resource.class);

    private PermissionMatrix() {
    }

    private static Grant g(Scope scope, Action... actions) {
        Set<Action> set = EnumSet.noneOf(Action.class);
        for (Action a : actions) {
            set.add(a);
        }
        return new Grant(true, scope, Set.copyOf(set));
    }

    private static Grant all(Action... actions) {
        return g(Scope.ALL, actions);
    }

    private static final Grant NO = Grant.DENIED;

    private static void row(Resource resource, Grant owner, Grant admin, Grant accountant, Grant manager,
            Grant contributor, Grant auditor, Grant viewer) {
        Map<Role, Grant> cells = new EnumMap<>(Role.class);
        cells.put(OWNER, owner);
        cells.put(ADMIN, admin);
        cells.put(ACCOUNTANT, accountant);
        cells.put(MANAGER, manager);
        cells.put(CONTRIBUTOR, contributor);
        cells.put(AUDITOR, auditor);
        cells.put(VIEWER, viewer);
        TABLE.put(resource, cells);
    }

    static {
        row(TRANSACTIONS, all(CREATE, READ, UPDATE, ARCHIVE), all(READ), all(CREATE, READ, UPDATE, ARCHIVE),
                g(Scope.ASSIGNED, READ, CREATE), g(Scope.OWN, CREATE, READ, UPDATE), all(READ), g(Scope.IF_GRANTED, READ));
        row(EXPENSE_CLAIMS, all(CREATE, READ, UPDATE, ARCHIVE), all(READ), all(CREATE, READ, UPDATE, ARCHIVE),
                g(Scope.ASSIGNED, READ), g(Scope.OWN, CREATE, READ, UPDATE, ARCHIVE), all(READ), NO);
        row(INVOICES, all(CREATE, READ, UPDATE, ARCHIVE, ISSUE), all(READ), all(CREATE, READ, UPDATE, ARCHIVE, ISSUE),
                g(Scope.ASSIGNED, READ), NO, all(READ), g(Scope.IF_GRANTED, READ));
        row(PAYMENTS, all(CREATE, READ, UPDATE, ARCHIVE), all(READ), all(CREATE, READ, UPDATE, ARCHIVE), NO, NO,
                all(READ), NO);
        row(CLIENTS_VENDORS, all(CREATE, READ, UPDATE, ARCHIVE), all(READ), all(CREATE, READ, UPDATE, ARCHIVE),
                g(Scope.ASSIGNED, READ), g(Scope.LIMITED, READ), all(READ), NO);
        row(VENDOR_BANK_DETAILS, all(UPDATE, UNMASK), g(Scope.LIMITED, READ), all(UPDATE, UNMASK), NO, NO,
                g(Scope.LIMITED, READ), NO);
        row(PROJECTS, all(CREATE, READ, UPDATE, ARCHIVE), all(READ), all(CREATE, READ, UPDATE, ARCHIVE),
                g(Scope.ASSIGNED, CREATE, READ, UPDATE), g(Scope.ASSIGNED, READ), all(READ), g(Scope.IF_GRANTED, READ));
        row(DOCUMENTS, all(CREATE, READ, UPDATE, ARCHIVE), g(Scope.LIMITED, READ), all(CREATE, READ, UPDATE, ARCHIVE),
                g(Scope.ASSIGNED, READ), g(Scope.OWN, CREATE, READ), all(READ), NO);
        row(REPORTS, all(READ, EXPORT), g(Scope.LIMITED, READ), all(READ, EXPORT), g(Scope.ASSIGNED, READ), NO,
                all(READ, EXPORT), g(Scope.IF_GRANTED, READ));
        row(TAX_DATA, all(READ, EXPORT), NO, all(READ, EXPORT), NO, NO, all(READ), NO);
        row(IMPORTS_RECONCILIATION, all(CREATE, READ, UPDATE, ARCHIVE), NO, all(CREATE, READ, UPDATE, ARCHIVE), NO, NO,
                all(READ), NO);
        row(COMPANY_SETTINGS, all(CREATE, READ, UPDATE), all(CREATE, READ, UPDATE), all(READ), NO, NO, all(READ), NO);
        row(MEMBERS, all(CREATE, READ, UPDATE, ARCHIVE), all(CREATE, READ, UPDATE, ARCHIVE), NO, NO, NO,
                g(Scope.LIMITED, READ), NO);
        row(APPROVAL_POLICIES, all(CREATE, READ, UPDATE, ARCHIVE), all(CREATE, READ, UPDATE, ARCHIVE), all(READ),
                all(READ), NO, all(READ), NO);
        row(APPROVAL_DECISIONS, all(APPROVE), all(APPROVE), all(APPROVE), all(APPROVE), all(APPROVE), NO, NO);
        row(INTEGRATIONS, all(CREATE, READ, UPDATE, ARCHIVE), all(CREATE, READ, UPDATE, ARCHIVE),
                g(Scope.LIMITED, READ), NO, NO, g(Scope.LIMITED, READ), NO);
        row(API_KEYS_WEBHOOKS, all(CREATE, READ, UPDATE, ARCHIVE), all(CREATE, READ, UPDATE, ARCHIVE), NO, NO, NO, NO, NO);
        row(BULK_EXPORTS, all(EXPORT), NO, all(EXPORT), g(Scope.ASSIGNED, EXPORT), g(Scope.OWN, EXPORT), all(EXPORT), NO);
        row(AUDIT_LOG, all(READ, EXPORT), g(Scope.LIMITED, READ), g(Scope.LIMITED, READ), NO, g(Scope.OWN, READ),
                all(READ, EXPORT), NO);
        row(BILLING, all(CREATE, READ, UPDATE, ARCHIVE), g(Scope.LIMITED, READ), NO, NO, NO, NO, NO);
        row(WORKSPACE_LIFECYCLE, all(EXECUTE), NO, NO, NO, NO, NO, NO);
    }

    /** Default deny: an unknown combination, a null argument or an unlisted cell is denied. */
    public static Grant check(Role role, Resource resource, Action action) {
        if (role == null || resource == null || action == null) {
            return Grant.DENIED;
        }
        Grant cell = TABLE.getOrDefault(resource, Map.of()).getOrDefault(role, Grant.DENIED);
        return cell.allowed() && cell.actions().contains(action) ? cell : Grant.DENIED;
    }

    public static boolean allows(Role role, Resource resource, Action action) {
        return check(role, resource, action).allowed();
    }

    /** All actions the matrix grants (any scope) for the cell; used by tests and by the UI capability list. */
    public static Set<Action> actions(Role role, Resource resource) {
        Grant cell = TABLE.getOrDefault(resource, Map.of()).getOrDefault(role, Grant.DENIED);
        return cell.allowed() ? cell.actions() : NONE;
    }
}
