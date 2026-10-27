package com.finora.finance.step22;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.finance.accounting.AccountingService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import tools.jackson.databind.JsonNode;

/** The integration registry (real states only), failure and recovery notifications, redacted error history and the workspace administration overview. */
class IntegrationsAdminTest extends Step22Support {

    @Autowired AccountingService accounting;

    private JsonNode entry(Biz b, String key) throws Exception {
        for (JsonNode i : bget(b, "/finance-integrations").body().path("items")) {
            if (key.equals(i.path("key").asString())) {
                return i;
            }
        }
        throw new AssertionError("no " + key);
    }

    private void connection(Biz b, String status) {
        jdbc.sql("insert into accounting_connections (id, tenant_id, provider, status, access_token_enc, created_by, created_at, updated_at) values (:id, :t, 'someprovider', :s, 'v1:SECRET-CIPHER', :u, now(), now())")
                .param("id", UUID.randomUUID()).param("t", b.space()).param("s", status).param("u", b.owner()).update();
    }

    @Test
    void everyExternalIntegrationIsHonestlyNotConfiguredAndNothingIsContacted() throws Exception {
        Biz b = biz();
        Resp r = bget(b, "/finance-integrations");
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.body().path("items")).extracting(i -> i.path("key").asString()).containsExactly("accounting", "ai", "ocr", "exchange_rates", "notification_delivery", "payments");
        for (JsonNode i : r.body().path("items")) {
            assertThat(i.path("state").asString()).as(i.path("key").asString()).isEqualTo("NOT_CONFIGURED");
            assertThat(i.path("message").asString()).isNotBlank();
        }
        JsonNode acct = entry(b, "accounting");
        assertThat(acct.path("message").asString()).contains("Nothing is connected, synchronised or sent");
        assertThat(acct.path("pending_decisions").toString()).contains("Which accounting provider(s) are approved");
        assertThat(acct.path("health").path("last_successful_operation_at").isNull()).isTrue();
        assertThat(entry(b, "ai").path("health").path("live_check").asString()).isEqualTo("not performed");
        assertThat(entry(b, "notification_delivery").path("message").asString()).contains("nothing is sent outside it");
        assertThat(entry(b, "payments").path("message").asString()).contains("No payment can be taken");
        assertThat(r.raw().body()).doesNotContain("CONNECTED\"");
    }

    @Test
    void connectionStateIsDerivedFromRealRowsAndTheLatestOperation() throws Exception {
        Biz b = biz();
        connection(b, "CONNECTED");
        assertThat(entry(b, "accounting").path("state").asString()).isEqualTo("CONNECTED");
        UUID ok = accounting.startRun(b.space(), "someprovider", "EXPORT", clock);
        accounting.finishRun(b.space(), ok, "SUCCEEDED", 5, 0, 1, null, null, clock);
        clock.advance(java.time.Duration.ofMinutes(5));
        assertThat(entry(b, "accounting").path("health").path("last_successful_operation_at").asString()).startsWith("2026-03-15");
        UUID bad = accounting.startRun(b.space(), "someprovider", "EXPORT", clock);
        accounting.finishRun(b.space(), bad, "FAILED", 0, 3, 3, "RATE_LIMITED", "429. Authorization: Bearer abc.def.ghi-123456 refresh_token=zzzzzzzzzzzz", clock);
        JsonNode failed = entry(b, "accounting");
        assertThat(failed.path("state").asString()).as("the latest operation failed").isEqualTo("FAILED");
        assertThat(failed.path("health").path("last_error_code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(failed.toString()).doesNotContain("abc.def").doesNotContain("zzzzzzzzzzzz").doesNotContain("SECRET-CIPHER");
        clock.advance(java.time.Duration.ofMinutes(5));
        UUID recovered = accounting.startRun(b.space(), "someprovider", "EXPORT", clock);
        accounting.finishRun(b.space(), recovered, "SUCCEEDED", 3, 0, 1, null, null, clock);
        assertThat(entry(b, "accounting").path("state").asString()).as("recovered").isEqualTo("CONNECTED");
        Biz expired = biz();
        connection(expired, "EXPIRED");
        assertThat(entry(expired, "accounting").path("state").asString()).isEqualTo("FAILED");
        Biz revoked = biz();
        connection(revoked, "REVOKED");
        assertThat(entry(revoked, "accounting").path("state").asString()).isEqualTo("DISCONNECTED");
        Biz pending = biz();
        connection(pending, "PENDING_CONSENT");
        assertThat(entry(pending, "accounting").path("state").asString()).isEqualTo("CONFIGURED");
        assertThat(entry(biz(), "accounting").path("state").asString()).as("another workspace is unaffected").isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void failuresAndRecoveryRaiseNotificationsOnceAndNeverCarryCredentials(CapturedOutput output) throws Exception {
        Biz b = biz();
        UUID fail = accounting.startRun(b.space(), "someprovider", "EXPORT", clock);
        accounting.finishRun(b.space(), fail, "FAILED", 0, 2, 3, "AUTH_EXPIRED", "token expired: Bearer abc.def.ghi-123456", clock);
        JsonNode n = as(b, b.owner(), "OWNER", "GET", "/finance-notifications?category=INTEGRATION", null).body();
        assertThat(n.path("total").asLong()).isEqualTo(1);
        assertThat(n.path("items").get(0).path("severity").asString()).isEqualTo("CRITICAL");
        assertThat(n.path("items").get(0).path("body").asString()).contains("failed").contains("AUTH_EXPIRED").contains("Financial data in Finora is unaffected");
        assertThat(n.toString()).doesNotContain("abc.def");
        clock.advance(java.time.Duration.ofMinutes(1));
        UUID ok = accounting.startRun(b.space(), "someprovider", "EXPORT", clock);
        accounting.finishRun(b.space(), ok, "SUCCEEDED", 4, 0, 1, null, null, clock);
        JsonNode after = as(b, b.owner(), "OWNER", "GET", "/finance-notifications?category=INTEGRATION", null).body();
        assertThat(after.path("total").asLong()).isEqualTo(2);
        assertThat(after.path("items").toString()).contains("recovered");
        UUID again = accounting.startRun(b.space(), "someprovider", "EXPORT", clock);
        accounting.finishRun(b.space(), again, "SUCCEEDED", 1, 0, 1, null, null, clock);
        assertThat(as(b, b.owner(), "OWNER", "GET", "/finance-notifications?category=INTEGRATION", null).body().path("total").asLong()).as("no recovery notice without an earlier failure").isEqualTo(2);
        assertThat(as(b, b.owner(), "OWNER", "GET", "/finance-notifications", null).raw().body()).doesNotContain("abc.def");
        assertThat(output.getAll()).doesNotContain("abc.def.ghi");
        Biz other = biz();
        assertThat(as(other, other.owner(), "OWNER", "GET", "/finance-notifications?category=INTEGRATION", null).body().path("total").asLong()).isZero();
    }

    @Test
    void theErrorHistoryListsOnlyFailuresWithRedactedMessages() throws Exception {
        Biz b = biz();
        UUID fail = accounting.startRun(b.space(), "someprovider", "IMPORT", clock);
        accounting.finishRun(b.space(), fail, "PARTIAL", 3, 2, 2, "SERVER_ERROR", "api_key=sk-live-1234567890123456789012345678901234 client_secret=hunter2", clock);
        UUID ok = accounting.startRun(b.space(), "someprovider", "IMPORT", clock);
        accounting.finishRun(b.space(), ok, "SUCCEEDED", 3, 0, 1, null, null, clock);
        Resp r = bget(b, "/finance-integrations/accounting/errors");
        assertThat(r.body().path("items")).hasSize(1);
        assertThat(r.body().path("items").get(0).path("status").asString()).isEqualTo("PARTIAL");
        assertThat(r.body().path("items").get(0).path("error_code").asString()).isEqualTo("SERVER_ERROR");
        assertThat(r.raw().body()).doesNotContain("sk-live").doesNotContain("hunter2").contains("[redacted]");
        assertThat(bget(b, "/finance-integrations/ai/errors").body().path("items")).isEmpty();
        assertThat(as(biz(), UUID.randomUUID(), "OWNER", "GET", "/finance-integrations/accounting/errors", null).body().path("items")).isEmpty();
    }

    @Test
    void integrationViewsFollowCompanySettingsRightsAndWorkspaceBoundaries() throws Exception {
        Biz b = biz();
        for (String role : List.of("OWNER", "ADMIN", "ACCOUNTANT")) {
            assertThat(as(b, UUID.randomUUID(), role, "GET", "/finance-integrations", null).status()).as(role).isEqualTo(200);
        }
        for (String role : List.of("VIEWER", "CONTRIBUTOR", "NOT_A_ROLE")) {
            assertThat(as(b, UUID.randomUUID(), role, "GET", "/finance-integrations", null).status()).as(role).isEqualTo(403);
            assertThat(as(b, UUID.randomUUID(), role, "GET", "/finance-integrations/accounting/errors", null).status()).as(role).isEqualTo(403);
            assertThat(as(b, UUID.randomUUID(), role, "GET", "/finance-admin/overview", null).status()).as(role).isEqualTo(403);
        }
        Biz other = biz();
        assertThat(raw("GET", "/v1/spaces/" + b.space() + "/finance-integrations", btoken(other, other.owner(), "OWNER"), null).status()).isEqualTo(404);
        assertThat(raw("GET", "/v1/spaces/" + b.space() + "/finance-admin/overview", btoken(other, other.owner(), "OWNER"), null).status()).isEqualTo(404);
        Person p = person();
        assertThat(get(p, "/finance-integrations").status()).isEqualTo(200);
        assertThat(raw("GET", base(p) + "/finance-integrations", token(p, p.space(), "ADMIN", List.of("workspace:personal")), null).status()).isEqualTo(403);
        assertThat(as(b, b.owner(), "OWNER", "POST", "/finance-integrations", Map.of()).status()).as("the registry has no mutating route").isIn(404, 405);
    }

    @Test
    void theWorkspaceAdministrationOverviewSummarisesOnlyThisWorkspaceAndSaysItIsNotPlatformAdministration() throws Exception {
        Biz b = biz();
        ok(bpost(b, "/finance-subscription/change", Map.of("plan_key", "limited")));
        ok(as(b, b.owner(), "OWNER", "POST", "/finance-alerts/evaluate", Map.of()));
        connection(b, "CONNECTED");
        UUID fail = accounting.startRun(b.space(), "someprovider", "EXPORT", clock);
        accounting.finishRun(b.space(), fail, "FAILED", 0, 1, 1, "X", "boom", clock);
        Resp r = bget(b, "/finance-admin/overview");
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.text("scope")).isEqualTo("WORKSPACE");
        assertThat(r.text("note")).contains("this workspace only").contains("Platform-wide administration does not exist yet");
        assertThat(r.body().path("subscription").path("plan").asString()).isEqualTo("limited");
        assertThat(r.body().path("subscription").path("provisional").asBoolean()).isTrue();
        assertThat(r.body().path("integrations_by_state").path("NOT_CONFIGURED").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(r.body().path("integrations_by_state").path("FAILED").asInt()).isEqualTo(1);
        assertThat(r.body().path("billing").path("provider_status").asString()).isEqualTo("NOT_CONFIGURED");
        assertThat(r.body().path("unread_notifications").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(r.raw().body()).doesNotContain("someprovider-secret");
        Resp otherView = as(biz(), UUID.randomUUID(), "OWNER", "GET", "/finance-admin/overview", null);
        assertThat(otherView.body().path("integrations_by_state").path("FAILED").isMissingNode()).as("another workspace sees none of it").isTrue();
        assertThat(otherView.body().path("subscription").path("plan").asString()).isEqualTo("standard");
    }
}
