package com.finora.finance.insights;

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
import com.finora.finance.business.BusinessContext;
import com.finora.finance.core.FinanceContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Who is calling and for which workspace (personal profile or business workspace) for the cross-cutting features: reports,
 * forecasts, imports, documents and reconciliation. Identity, workspace and role come only from the verified internal token. Permissions
 * come from the same {@link PermissionMatrix} as everything else: personal workspaces belong to their Owner; business roles are mapped to
 * REPORTS, IMPORTS_RECONCILIATION and DOCUMENTS. Object scopes this service cannot evaluate fail closed.
 */
public record InsightsContext(Domain domain, UUID tenantId, UUID userId, Role role, ZoneId zone, Clock clock, int fiscalStartMonth, String displayCurrency,
        int rateStaleAfterDays) {

    public static final int DEFAULT_FISCAL_START_MONTH = 1;
    public static final int DEFAULT_RATE_STALE_DAYS = 7;

    /** The context with the regional defaults that preserve the behaviour from before Step 21 (calendar fiscal year, no display currency). */
    public InsightsContext(Domain domain, UUID tenantId, UUID userId, Role role, ZoneId zone, Clock clock) {
        this(domain, tenantId, userId, role, zone, clock, DEFAULT_FISCAL_START_MONTH, null, DEFAULT_RATE_STALE_DAYS);
    }

    public enum Domain { PERSONAL, BUSINESS }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    public boolean business() {
        return domain == Domain.BUSINESS;
    }

    private void personalOwner() {
        if (role != Role.OWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN, null);
        }
    }

    private Scope scope(Resource resource, Action action) {
        Grant grant = PermissionMatrix.check(role, resource, action);
        if (!grant.allowed() || (grant.scope() != Scope.ALL && grant.scope() != Scope.OWN)) {
            throw new ApiException(ErrorCode.FORBIDDEN, null);
        }
        return grant.scope();
    }

    private void all(Resource resource, Action action) {
        if (scope(resource, action) != Scope.ALL) {
            throw new ApiException(ErrorCode.FORBIDDEN, null);
        }
    }

    /** Reading reports: the owner of a personal profile; REPORTS read (full or the aggregate-only Admin view) in a business. */
    public void requireReports() {
        if (!business()) {
            personalOwner();
            return;
        }
        Grant grant = PermissionMatrix.check(role, Resource.REPORTS, Action.READ);
        if (!grant.allowed() || (grant.scope() != Scope.ALL && grant.scope() != Scope.LIMITED)) {
            throw new ApiException(ErrorCode.FORBIDDEN, null);
        }
    }

    /** Record-level detail (individual records behind a report): the personal owner, or REPORTS read with full scope (not the Admin aggregate-only view). */
    public void requireFullReports() {
        if (!business()) {
            personalOwner();
            return;
        }
        all(Resource.REPORTS, Action.READ);
    }

    /** Regional settings are readable by anyone who may read reports or company settings (they only shape how figures are displayed). */
    public void requireRegionalRead() {
        if (!business()) {
            personalOwner();
            return;
        }
        Grant settings = PermissionMatrix.check(role, Resource.COMPANY_SETTINGS, Action.READ);
        Grant reports = PermissionMatrix.check(role, Resource.REPORTS, Action.READ);
        boolean ok = (settings.allowed() && settings.scope() == Scope.ALL) || (reports.allowed() && (reports.scope() == Scope.ALL || reports.scope() == Scope.LIMITED));
        if (!ok) {
            throw new ApiException(ErrorCode.FORBIDDEN, null);
        }
    }

    /** Company-settings level read: the personal owner, or full COMPANY_SETTINGS read in a business (administrative information such as integrations and subscriptions). */
    public void requireSettingsRead() {
        if (!business()) {
            personalOwner();
            return;
        }
        all(Resource.COMPANY_SETTINGS, Action.READ);
    }

    /** Changing regional settings, exchange rates or GST registration: the personal owner, or full company-settings update rights. */
    public void requireSettingsWrite() {
        if (!business()) {
            personalOwner();
            return;
        }
        all(Resource.COMPANY_SETTINGS, Action.UPDATE);
    }

    /** Exporting a report needs the export right (not just read). */
    public void requireExport() {
        if (!business()) {
            personalOwner();
            return;
        }
        all(Resource.REPORTS, Action.EXPORT);
    }

    /** Imports and reconciliation share the IMPORTS_RECONCILIATION matrix row. */
    public void requireImports(Action action) {
        if (!business()) {
            personalOwner();
            return;
        }
        all(Resource.IMPORTS_RECONCILIATION, action);
    }

    /** Documents: ALL, or OWN (a Contributor sees and handles only the documents they uploaded). Personal: the owner, all. */
    public Scope documents(Action action) {
        if (!business()) {
            personalOwner();
            return Scope.ALL;
        }
        return scope(Resource.DOCUMENTS, action);
    }

    public FinanceContext personal() {
        return new FinanceContext(tenantId, userId, role, zone, clock);
    }

    public BusinessContext businessContext() {
        return new BusinessContext(tenantId, userId, role, zone, clock);
    }

    static InsightsContext from(InternalPrincipal p, UUID pathSpace, ZoneId zone, Clock clock, Domain wanted) {
        if (p == null || p.kind() != PrincipalKind.USER) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED, null);
        }
        if (p.tenantId() == null || !p.tenantId().equals(pathSpace)) {
            throw new ApiException(ErrorCode.NOT_FOUND, null);
        }
        Domain domain;
        if (p.scopes().contains(BusinessContext.BUSINESS_SCOPE)) {
            domain = Domain.BUSINESS;
        } else if (p.scopes().contains(FinanceContext.PERSONAL_SCOPE)) {
            domain = Domain.PERSONAL;
        } else {
            throw new ApiException(ErrorCode.CONFLICT, "Finance is not available in this workspace");
        }
        if (wanted != null && wanted != domain) {
            throw new ApiException(ErrorCode.CONFLICT, "This is not available in this kind of workspace");
        }
        Role role = Role.parse(p.role()).orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, null));
        return new InsightsContext(domain, p.tenantId(), UUID.fromString(p.subject()), role, zone, clock);
    }
}
