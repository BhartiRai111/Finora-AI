package com.finora.finance.step21;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.finance.BusinessTestSupport;
import com.finora.finance.accounting.AccountingService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import tools.jackson.databind.JsonNode;

/** No accounting provider is approved: the state says so, nothing can connect or sync, and the reusable foundations (duplicate events, sync ledger, redaction) behave. */
class AccountingFoundationTest extends BusinessTestSupport {

    @Autowired AccountingService accounting;

    @Test
    void statusAdmitsThereIsNoProviderAndListsWhatIsPending() throws Exception {
        Biz b = biz();
        Resp r = bget(b, "/finance-accounting");
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.body().path("provider").path("status").asString()).isEqualTo("NOT_CONFIGURED");
        assertThat(r.body().path("provider").path("key").asString()).isEqualTo("none");
        assertThat(r.body().path("provider").path("approved").asBoolean()).isFalse();
        assertThat(r.text("message")).contains("No accounting provider has been approved or configured").contains("Nothing is connected");
        assertThat(r.body().path("capabilities").path("oauth_connect").asBoolean()).isFalse();
        assertThat(r.body().path("capabilities").path("webhooks").asBoolean()).isFalse();
        assertThat(r.body().path("capabilities").path("sync").asBoolean()).isFalse();
        assertThat(r.body().path("token_vault").path("available").asBoolean()).as("no key is configured in tests").isFalse();
        assertThat(r.body().path("connections")).isEmpty();
        assertThat(r.body().path("pending_decisions").toString()).contains("Which accounting provider(s) are approved").contains("OAuth client registration").contains("token-vault key");
        assertThat(bget(b, "/finance-accounting/sync-runs").body().path("items")).isEmpty();
    }

    @Test
    void thereAreNoConnectWebhookOrSyncRoutes() throws Exception {
        Biz b = biz();
        for (String path : List.of("/finance-accounting/connect", "/finance-accounting/webhook", "/finance-accounting/sync", "/finance-accounting/oauth/callback")) {
            assertThat(as(b, b.owner(), "OWNER", "POST", path, java.util.Map.of()).status()).as(path).isIn(404, 405);
            assertThat(as(b, b.owner(), "OWNER", "GET", path, null).status()).as(path).isIn(404, 405);
        }
    }

    @Test
    void rolesWorkspacesAndPersonalProfilesAreEnforced() throws Exception {
        Biz b = biz();
        UUID user = UUID.randomUUID();
        for (String role : List.of("OWNER", "ADMIN", "ACCOUNTANT", "AUDITOR")) {
            assertThat(as(b, user, role, "GET", "/finance-accounting", null).status()).as(role).isEqualTo(200);
        }
        for (String role : List.of("VIEWER", "CONTRIBUTOR", "NOT_A_ROLE")) {
            assertThat(as(b, user, role, "GET", "/finance-accounting", null).status()).as(role).isEqualTo(403);
            assertThat(as(b, user, role, "GET", "/finance-accounting/sync-runs", null).status()).as(role).isEqualTo(403);
        }
        Biz other = biz();
        assertThat(raw("GET", "/v1/spaces/" + b.space() + "/finance-accounting", btoken(other, other.owner(), "OWNER"), null).status()).isEqualTo(404);
        Person p = person();
        assertThat(get(p, "/finance-accounting").status()).isEqualTo(409);
    }

    @Test
    void storedTokensNeverLeaveTheDatabaseThroughAnyEndpoint() throws Exception {
        Biz b = biz();
        jdbc.sql("""
                insert into accounting_connections (id, tenant_id, provider, status, access_token_enc, refresh_token_enc, token_expires_at, scopes, created_by, created_at, updated_at)
                values (:id, :t, 'someprovider', 'CONNECTED', 'v1:ACCESS-CIPHERTEXT', 'v1:REFRESH-CIPHERTEXT', now() + interval '1 hour', 'read', :u, now(), now())
                """).param("id", UUID.randomUUID()).param("t", b.space()).param("u", b.owner()).update();
        Resp r = bget(b, "/finance-accounting");
        assertThat(r.body().path("connections")).hasSize(1);
        JsonNode c = r.body().path("connections").get(0);
        assertThat(c.path("provider").asString()).isEqualTo("someprovider");
        assertThat(c.path("status").asString()).isEqualTo("CONNECTED");
        assertThat(r.raw().body()).doesNotContain("CIPHERTEXT").doesNotContain("access_token").doesNotContain("refresh_token");
        Biz other = biz();
        assertThat(bget(other, "/finance-accounting").body().path("connections")).as("another workspace sees none").isEmpty();
    }

    @Test
    void aDuplicateWebhookEventIsRecognisedOnceAcrossRetriesAndPerProvider() throws Exception {
        UUID t = UUID.randomUUID();
        String run = UUID.randomUUID().toString().substring(0, 8);
        String a = "prov-a-" + run;
        String bb = "prov-b-" + run;
        byte[] payload = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(accounting.firstDelivery(a, "evt_1", t, payload, clock)).isTrue();
        assertThat(accounting.firstDelivery(a, "evt_1", t, payload, clock)).as("redelivery").isFalse();
        assertThat(accounting.firstDelivery(a, "evt_1", t, "{\"id\":\"evt_1\",\"changed\":true}".getBytes(StandardCharsets.UTF_8), clock)).as("same id, different payload is still a duplicate").isFalse();
        assertThat(accounting.firstDelivery(bb, "evt_1", t, payload, clock)).as("another provider's id space").isTrue();
        assertThat(accounting.firstDelivery(a, "evt_2", t, payload, clock)).isTrue();
        var results = concurrently(List.of(() -> accounting.firstDelivery(a, "evt_race", t, payload, clock) ? 1 : 0, () -> accounting.firstDelivery(a, "evt_race", t, payload, clock) ? 1 : 0,
                () -> accounting.firstDelivery(a, "evt_race", t, payload, clock) ? 1 : 0));
        assertThat(results.stream().mapToInt(Integer::intValue).sum()).as("exactly one concurrent delivery wins").isEqualTo(1);
        assertThat(jdbc.sql("select string_agg(payload_hash, ',') from accounting_webhook_events where tenant_id = :t").param("t", t).query(String.class).single()).doesNotContain("evt_1\"");
    }

    @Test
    void theSyncLedgerKeepsStatusCountsAndOnlyRedactedErrors(CapturedOutput output) throws Exception {
        Biz b = biz();
        UUID run = accounting.startRun(b.space(), "someprovider", "EXPORT", clock);
        accounting.finishRun(b.space(), run, "PARTIAL", 7, 2, 3, "RATE_LIMITED", "429 from provider. Authorization: Bearer abc.def.ghi-123456 refresh_token=zzzzzzzzzzzzzz retry later", clock);
        UUID failed = accounting.startRun(b.space(), "someprovider", "IMPORT", clock);
        accounting.finishRun(b.space(), failed, "FAILED", 0, 0, 1, "AUTH_EXPIRED", "token expired", clock);
        JsonNode items = bget(b, "/finance-accounting/sync-runs").body().path("items");
        assertThat(items).hasSize(2);
        JsonNode partial = null;
        for (JsonNode i : items) {
            if (run.toString().equals(i.path("id").asString())) {
                partial = i;
            }
        }
        assertThat(partial.path("status").asString()).isEqualTo("PARTIAL");
        assertThat(partial.path("records_ok").asInt()).isEqualTo(7);
        assertThat(partial.path("records_failed").asInt()).isEqualTo(2);
        assertThat(partial.path("attempts").asInt()).isEqualTo(3);
        assertThat(partial.path("error_code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(partial.path("error_message").asString()).contains("429 from provider").contains("[redacted]").doesNotContain("abc.def").doesNotContain("zzzzzzzz");
        Biz other = biz();
        assertThat(bget(other, "/finance-accounting/sync-runs").body().path("items")).isEmpty();
        assertThat(output.getAll()).doesNotContain("abc.def.ghi").doesNotContain("zzzzzzzzzzzzzz");
    }
}
