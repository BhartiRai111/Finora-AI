package com.finora.commons;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.commons.authz.Action;
import com.finora.commons.authz.PermissionMatrix;
import com.finora.commons.authz.Resource;
import com.finora.commons.authz.Role;
import com.finora.commons.authz.Scope;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * PERM-1: the code table must equal the documented matrix. The PRD table is copied verbatim into a test resource and
 * parsed independently of {@link PermissionMatrix}; every role x resource cell is compared.
 */
class PermissionMatrixTest {

    private static final Role[] COLUMNS = {Role.OWNER, Role.ADMIN, Role.ACCOUNTANT, Role.MANAGER, Role.CONTRIBUTOR,
            Role.AUDITOR, Role.VIEWER};
    private static final Resource[] ROWS = {Resource.TRANSACTIONS, Resource.EXPENSE_CLAIMS, Resource.INVOICES,
            Resource.PAYMENTS, Resource.CLIENTS_VENDORS, Resource.VENDOR_BANK_DETAILS, Resource.PROJECTS,
            Resource.DOCUMENTS, Resource.REPORTS, Resource.TAX_DATA, Resource.IMPORTS_RECONCILIATION,
            Resource.COMPANY_SETTINGS, Resource.MEMBERS, Resource.APPROVAL_POLICIES, Resource.APPROVAL_DECISIONS,
            Resource.INTEGRATIONS, Resource.API_KEYS_WEBHOOKS, Resource.BULK_EXPORTS, Resource.AUDIT_LOG, Resource.BILLING,
            Resource.WORKSPACE_LIFECYCLE};
    private static final Pattern LETTERS = Pattern.compile("\\b[CRUAX]+\\b");

    private static List<String[]> prdRows() throws IOException {
        String text;
        try (InputStream in = PermissionMatrixTest.class.getResourceAsStream("/prd-permission-matrix.md")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.startsWith("|") || line.startsWith("| Resource") || line.startsWith("|---")) {
                continue;
            }
            String[] cells = line.substring(1, line.length() - 1).split("\\|");
            for (int i = 0; i < cells.length; i++) {
                cells[i] = cells[i].trim();
            }
            if (cells.length == 8) {
                rows.add(cells);
            }
        }
        return rows;
    }

    /** Actions named by a PRD cell, derived only from the cell text. */
    private static Set<Action> documentedActions(String cell) {
        Set<Action> actions = EnumSet.noneOf(Action.class);
        Matcher m = LETTERS.matcher(cell);
        while (m.find()) {
            for (char c : m.group().toCharArray()) {
                actions.add(switch (c) {
                    case 'C' -> Action.CREATE;
                    case 'R' -> Action.READ;
                    case 'U' -> Action.UPDATE;
                    case 'A' -> Action.ARCHIVE;
                    default -> Action.EXPORT;
                });
            }
        }
        if (cell.contains("issue")) {
            actions.add(Action.ISSUE);
        }
        if (cell.equals("apr")) {
            actions.add(Action.APPROVE);
        }
        if (cell.contains("edit")) {
            actions.add(Action.UPDATE);
        }
        if (cell.contains("unmask")) {
            actions.add(Action.UNMASK);
        }
        if (cell.equals("masked") || cell.equals("own activity") || cell.equals("pick-list fields")) {
            actions.add(Action.READ);
        }
        if (cell.equals("Yes")) {
            actions.add(Action.EXECUTE);
        }
        return actions;
    }

    @Test
    void everyCellMatchesTheDocumentedMatrix() throws IOException {
        List<String[]> rows = prdRows();
        assertThat(rows).hasSize(ROWS.length);
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < COLUMNS.length; c++) {
                String cell = rows.get(r)[c + 1];
                assertThat(PermissionMatrix.actions(COLUMNS[c], ROWS[r]))
                        .as("%s / %s documented as '%s'", ROWS[r], COLUMNS[c], cell)
                        .isEqualTo(documentedActions(cell));
            }
        }
    }

    @Test
    void documentedScopesAreRecorded() throws IOException {
        List<String[]> rows = prdRows();
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < COLUMNS.length; c++) {
                String cell = rows.get(r)[c + 1];
                Scope expected = null;
                if (cell.startsWith("scope")) {
                    expected = Scope.ASSIGNED;
                } else if (cell.startsWith("own") && !cell.equals("own activity")) {
                    expected = Scope.OWN;
                } else if (cell.contains("if granted")) {
                    expected = Scope.IF_GRANTED;
                }
                if (expected != null) {
                    for (Action a : documentedActions(cell)) {
                        if (a == Action.READ || a == Action.CREATE || a == Action.EXPORT) {
                            assertThat(PermissionMatrix.check(COLUMNS[c], ROWS[r], a).scope())
                                    .as("%s / %s '%s'", ROWS[r], COLUMNS[c], cell).isEqualTo(expected);
                        }
                    }
                }
            }
        }
    }

    @Test
    void anythingNotListedIsDenied() {
        for (Role role : Role.values()) {
            for (Resource resource : Resource.values()) {
                for (Action action : Action.values()) {
                    boolean documented = PermissionMatrix.actions(role, resource).contains(action);
                    assertThat(PermissionMatrix.allows(role, resource, action)).isEqualTo(documented);
                }
            }
        }
        assertThat(PermissionMatrix.allows(null, Resource.MEMBERS, Action.READ)).isFalse();
        assertThat(PermissionMatrix.allows(Role.OWNER, null, Action.READ)).isFalse();
        assertThat(PermissionMatrix.allows(Role.OWNER, Resource.MEMBERS, null)).isFalse();
    }

    @Test
    void approvalAuthorityIsNeverGrantedToAuditorOrViewer() {
        assertThat(PermissionMatrix.allows(Role.AUDITOR, Resource.APPROVAL_DECISIONS, Action.APPROVE)).isFalse();
        assertThat(PermissionMatrix.allows(Role.VIEWER, Resource.APPROVAL_DECISIONS, Action.APPROVE)).isFalse();
    }

    @Test
    void onlyTheOwnerMayExecuteWorkspaceLifecycleChanges() {
        for (Role role : Role.values()) {
            assertThat(PermissionMatrix.allows(role, Resource.WORKSPACE_LIFECYCLE, Action.EXECUTE))
                    .isEqualTo(role == Role.OWNER);
        }
    }

    @Test
    void adminCanGrantOnlyUpToAccountantLevel() {
        assertThat(Role.OWNER.canGrant(Role.OWNER)).isTrue();
        for (Role target : Role.values()) {
            boolean expected = target != Role.OWNER && target != Role.ADMIN;
            assertThat(Role.ADMIN.canGrant(target)).as(target.name()).isEqualTo(expected);
            assertThat(Role.ACCOUNTANT.canGrant(target)).isFalse();
            assertThat(Role.VIEWER.canGrant(target)).isFalse();
        }
        assertThat(Role.requiresMfaFor()).isEqualTo(Set.of(Role.OWNER, Role.ADMIN));
    }
}
