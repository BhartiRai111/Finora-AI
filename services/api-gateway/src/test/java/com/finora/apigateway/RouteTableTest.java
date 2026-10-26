package com.finora.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RouteTableTest {

    private final RouteTable table = RouteTable.standard();
    private final String s = "/v1/spaces/" + UUID.randomUUID();

    private String owner(String path) {
        return table.match(path).map(Route::service).orElse("NONE");
    }

    @Test
    void routesToTheOwningService() {
        assertThat(owner("/v1/auth/login")).isEqualTo("identity-access");
        assertThat(owner("/v1/me")).isEqualTo("identity-access");
        assertThat(owner(s + "/members/42")).isEqualTo("identity-access");
        assertThat(owner(s + "/transactions")).isEqualTo("finance");
        assertThat(owner(s + "/transactions/abc/tags")).isEqualTo("finance");
        assertThat(owner(s + "/invoices/9")).isEqualTo("finance");
        assertThat(owner(s + "/documents/1")).isEqualTo("document");
        assertThat(owner(s + "/imports")).isEqualTo("ingestion-integration");
        assertThat(owner(s + "/reports/monthly")).isEqualTo("reporting");
        assertThat(owner(s + "/ai/chat")).isEqualTo("intelligence");
        assertThat(owner(s + "/notifications")).isEqualTo("platform");
        assertThat(owner(s + "/audit/events")).isEqualTo("audit");
    }

    @Test
    void longestMatchResolvesTheReconciliationPeriodsCollision() {
        // PD-22: lives under the finance-owned "accounts" prefix but is owned by ingestion-integration.
        assertThat(owner(s + "/accounts/7")).isEqualTo("finance");
        assertThat(owner(s + "/accounts/7/reconciliation-periods")).isEqualTo("ingestion-integration");
        assertThat(owner(s + "/accounts/7/reconciliation-periods/2026-01")).isEqualTo("ingestion-integration");
        assertThat(owner(s + "/accounts/7/something-else")).isEqualTo("finance");
    }

    @Test
    void specificMePathBeatsTheWildcard() {
        assertThat(owner("/v1/me/notification-preferences")).isEqualTo("platform");
        assertThat(owner("/v1/me/sessions")).isEqualTo("identity-access");
    }

    @Test
    void webhooksRouteByProviderKind() {
        assertThat(owner("/v1/webhooks/billing/acme")).isEqualTo("platform");
        assertThat(owner("/v1/webhooks/acme")).isEqualTo("ingestion-integration");
    }

    @Test
    void internalAdminAndUnknownPathsAreNeverRouted() {
        assertThat(owner("/internal/v1/ping")).isEqualTo("NONE");
        assertThat(owner("/admin/v1/tenants")).isEqualTo("NONE");
        assertThat(owner("/v1/unknown")).isEqualTo("NONE");
        assertThat(owner("/v1/spaces/" + UUID.randomUUID() + "/internal-things")).isEqualTo("NONE");
        assertThat(owner("/v2/me")).isEqualTo("NONE");
    }

    @Test
    void noRoutePatternTargetsAnInternalPath() {
        assertThat(table.routes()).noneMatch(r -> r.pattern().contains("/internal") || r.pattern().startsWith("/admin"));
    }

    @Test
    void duplicatePatternsAreRejectedAtStartup() {
        Route a = Route.of("/v1/x", "finance", Route.Access.SESSION_REQUIRED);
        Route b = Route.of("/v1/x", "document", Route.Access.SESSION_REQUIRED);
        assertThatThrownBy(() -> new RouteTable(List.of(a, b))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyRoutedServiceIsAKnownService() {
        assertThat(table.services()).isSubsetOf(Stubs.SERVICES);
    }
}
