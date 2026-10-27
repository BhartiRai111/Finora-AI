package com.finora.apigateway;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Public path -> owning service (Step 08b §2.1, PD-22). Patterns are explicit; when several match, the most specific
 * pattern wins (Spring {@link PathPattern#SPECIFICITY_COMPARATOR}). Anything unmatched is not routed. Internal
 * ({@code /internal/**}) and admin ({@code /admin/**}) paths are deliberately absent.
 */
public final class RouteTable {

    private static final Set<String> FINANCE = Set.of("accounts", "transactions", "transfers", "categories", "tags",
            "recurring-rules", "budgets", "goals", "subscriptions", "bills", "clients", "vendors", "projects", "claims",
            "approvals", "approval-policies", "invoices", "adjustments", "payments", "vendor-bills", "invoice-series",
            "companies", "net-worth", "finance-settings", "summaries", "expenses", "expense-categories",
            "business-settings", "business-summaries");

    private final List<Route> routes;

    public RouteTable(List<Route> routes) {
        Set<String> seen = new HashSet<>();
        for (Route route : routes) {
            if (!seen.add(route.pattern())) {
                throw new IllegalStateException("Duplicate gateway route pattern: " + route.pattern());
            }
        }
        this.routes = List.copyOf(routes);
    }

    public List<Route> routes() {
        return routes;
    }

    public Optional<Route> match(String path) {
        PathContainer container = PathContainer.parsePath(path);
        return routes.stream()
                .filter(r -> r.compiled().matches(container))
                .min(Comparator.comparing(Route::compiled, PathPattern.SPECIFICITY_COMPARATOR));
    }

    public Set<String> services() {
        Set<String> names = new HashSet<>();
        routes.forEach(r -> names.add(r.service()));
        return names;
    }

    public static RouteTable standard() {
        List<Route> r = new ArrayList<>();
        // identity-access: credentials and sessions are public/optional-session and may set the session cookie.
        r.add(Route.of("/v1/auth/**", "identity-access", Route.Access.SESSION_OPTIONAL).withCookies().withStrictRate());
        // Session-bound operations: a session (and, for POST, the CSRF token) is mandatory; both manage the session cookie.
        r.add(Route.of("/v1/auth/logout", "identity-access", Route.Access.SESSION_REQUIRED).withCookies());
        r.add(Route.of("/v1/auth/password/change", "identity-access", Route.Access.SESSION_REQUIRED).withCookies());
        r.add(Route.of("/v1/auth/step-up", "identity-access", Route.Access.SESSION_REQUIRED).withStrictRate());
        for (String mfa : List.of("enroll", "confirm", "verify", "disable")) {
            r.add(Route.of("/v1/auth/mfa/" + mfa, "identity-access", Route.Access.SESSION_REQUIRED).withCookies().withStrictRate());
        }
        // Accepting or declining needs a signed-in user (and therefore a CSRF token).
        r.add(Route.of("/v1/invitations/accept", "identity-access", Route.Access.SESSION_REQUIRED).withStrictRate());
        r.add(Route.of("/v1/invitations/decline", "identity-access", Route.Access.SESSION_REQUIRED).withStrictRate());
        r.add(Route.of("/v1/spaces/{space}", "identity-access", Route.Access.SESSION_REQUIRED));
        r.add(Route.of("/v1/me", "identity-access", Route.Access.SESSION_REQUIRED));
        r.add(Route.of("/v1/me/notification-preferences", "platform", Route.Access.SESSION_REQUIRED));
        r.add(Route.of("/v1/me/**", "identity-access", Route.Access.SESSION_REQUIRED));
        r.add(Route.of("/v1/workspaces", "identity-access", Route.Access.SESSION_REQUIRED));
        r.add(Route.of("/v1/workspaces/**", "identity-access", Route.Access.SESSION_REQUIRED));
        for (String s : List.of("members", "invitations", "roles", "teams", "ownership-transfers")) {
            space(r, s, "identity-access");
        }
        for (String s : FINANCE) {
            space(r, s, "finance");
        }
        // Step 20: finance-assistant (read-only AI-assisted questions over the finance data) is also finance-owned for now; the reserved "ai" prefix of the intelligence service stays
        // unchanged until an approved internal contract exists (decision register, Step 20).
        // Step 19: reports, forecasts, imports, documents and reconciliation are served by the finance service (the owner of the data they read
        // and write). Uploads (CSV, PDF, PNG, JPEG as application/octet-stream or text/csv) are accepted on the import and document routes only.
        for (String s : List.of("finance-reports", "finance-forecast", "finance-reconciliation", "finance-assistant", "finance-regional", "finance-rates", "finance-gst", "finance-accounting", "finance-notifications", "finance-alerts", "finance-subscription", "finance-billing",
                "finance-integrations", "finance-admin")) {
            space(r, s, "finance");
        }
        for (String s : List.of("finance-imports", "finance-documents")) {
            r.add(Route.of("/v1/spaces/{space}/" + s + "/**", "finance", Route.Access.SESSION_REQUIRED).withUploads());
            r.add(Route.of("/v1/spaces/{space}/" + s, "finance", Route.Access.SESSION_REQUIRED).withUploads());
        }
        space(r, "tax", "finance");
        space(r, "fx", "finance");
        for (String s : List.of("documents", "extractions", "retention-policies", "legal-holds")) {
            r.add(Route.of("/v1/spaces/{space}/" + s + "/**", "document", Route.Access.SESSION_REQUIRED).withUploads());
            r.add(Route.of("/v1/spaces/{space}/" + s, "document", Route.Access.SESSION_REQUIRED).withUploads());
        }
        for (String s : List.of("imports", "import-templates", "statement-lines", "reconciliation", "integrations")) {
            Route.Access access = Route.Access.SESSION_REQUIRED;
            r.add(Route.of("/v1/spaces/{space}/" + s + "/**", "ingestion-integration", access).withUploads());
            r.add(Route.of("/v1/spaces/{space}/" + s, "ingestion-integration", access).withUploads());
        }
        // Collision from PD-22: lives under the finance-owned "accounts" prefix but belongs to ingestion-integration.
        r.add(Route.of("/v1/spaces/{space}/accounts/{id}/reconciliation-periods/**", "ingestion-integration",
                Route.Access.SESSION_REQUIRED));
        r.add(Route.of("/v1/spaces/{space}/accounts/{id}/reconciliation-periods", "ingestion-integration",
                Route.Access.SESSION_REQUIRED));
        for (String s : List.of("reports", "dashboards", "data-quality", "exports", "saved-views")) {
            space(r, s, "reporting");
        }
        space(r, "ai", "intelligence");
        space(r, "notifications", "platform");
        space(r, "billing", "platform");
        space(r, "support-access-grants", "platform");
        space(r, "audit", "audit");
        // Provider webhooks authenticate by signature inside the owning service, not by session.
        r.add(Route.of("/v1/webhooks/billing/{provider}", "platform", Route.Access.SESSION_OPTIONAL));
        r.add(Route.of("/v1/webhooks/{provider}", "ingestion-integration", Route.Access.SESSION_OPTIONAL));
        return new RouteTable(r);
    }

    private static void space(List<Route> r, String segment, String service) {
        r.add(Route.of("/v1/spaces/{space}/" + segment, service, Route.Access.SESSION_REQUIRED));
        r.add(Route.of("/v1/spaces/{space}/" + segment + "/**", service, Route.Access.SESSION_REQUIRED));
    }
}
