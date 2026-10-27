package com.finora.finance.step22;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.finance.accounting.WebhookVerifier;
import com.finora.finance.subscriptions.BillingEventProcessor;
import com.finora.finance.subscriptions.BillingEventProcessor.Outcome;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Plans, lifecycle, server-side entitlements and billing records; the billing event rules are exercised through the verified-event processor (no provider exists). */
class SubscriptionBillingTest extends Step22Support {

    @Autowired BillingEventProcessor processor;

    private static final byte[] SECRET = "test-shared-secret".getBytes(StandardCharsets.UTF_8);

    /** Event ids are global per provider and survive between test runs, so each test instance gets its own suffix. */
    private final String run = UUID.randomUUID().toString().substring(0, 8);

    private Outcome deliver(Map<String, Object> original) throws Exception {
        Map<String, Object> event = new java.util.LinkedHashMap<>(original);
        if (event.get("id") instanceof String id) {
            event.put("id", id + "-" + run);
        }
        byte[] body = json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        long ts = clock.instant().getEpochSecond();
        return processor.verifyAndProcess(SECRET, "testpay", String.valueOf(ts), body, WebhookVerifier.sign(SECRET, ts, body));
    }

    private String recordCreated(Biz b, String amount) throws Exception {
        String id = UUID.randomUUID().toString();
        Outcome o = deliver(env("evt-" + UUID.randomUUID(), "billing.record_created", b.space(), clock.instant().getEpochSecond(),
                Map.of("record_id", id, "amount", amount, "currency", "INR", "description", "Monthly service (provisional)")));
        assertThat(o.status()).as(o.detail()).isEqualTo("PROCESSED");
        return id;
    }

    // ------------------------------------------------------------------ plans and lifecycle

    @Test
    void aWorkspaceWithoutARowIsActiveOnTheProvisionalDefaultPlanAndEverythingKeepsWorking() throws Exception {
        Biz b = biz();
        Resp r = bget(b, "/finance-subscription");
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("ACTIVE");
        assertThat(r.body().path("implicit_default").asBoolean()).isTrue();
        assertThat(r.body().path("plan").path("plan_key").asString()).isEqualTo("standard");
        assertThat(r.body().path("plan").path("provisional").asBoolean()).isTrue();
        assertThat(r.body().path("notices").toString()).contains("provisional").contains("not an approved commercial offer").contains("no price applies");
        assertThat(r.body().path("effective")).hasSize(4);
        for (JsonNode e : r.body().path("effective")) {
            assertThat(e.path("enabled").asBoolean()).as(e.path("key").asString()).isTrue();
        }
        assertThat(bpost(b, "/finance-assistant/ask", Map.of("question", "How much did I spend last month?")).status()).as("existing behaviour is unchanged").isEqualTo(200);
        Resp plans = bget(b, "/finance-subscription/plans");
        assertThat(plans.body().path("items")).extracting(p -> p.path("plan_key").asString()).containsExactly("limited", "standard");
        for (JsonNode p : plans.body().path("items")) {
            assertThat(p.path("provisional").asBoolean()).isTrue();
            assertThat(p.path("requires_payment").asBoolean()).isFalse();
            assertThat(p.has("price")).isFalse();
        }
    }

    @Test
    void theOwnerSwitchesPlansAndTheServerEnforcesEveryEntitlementImmediately() throws Exception {
        Biz b = biz();
        Resp r = bpost(b, "/finance-subscription/change", Map.of("plan_key", "limited"));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.body().path("plan").path("plan_key").asString()).isEqualTo("limited");
        Resp ask = bpost(b, "/finance-assistant/ask", Map.of("question", "How much did I spend last month?"));
        assertThat(ask.status()).isEqualTo(403);
        assertThat(ask.text("detail")).contains("Your current plan (Limited (provisional)) does not include the finance assistant");
        assertThat(as(b, b.owner(), "OWNER", "POST", "/finance-assistant/what-if", Map.of("expense_change_percent", "5")).status()).isEqualTo(403);
        ok(as(b, b.owner(), "OWNER", "PUT", "/finance-gst/registration", Map.of("gst_registered", true, "gstin", "27AAPFU0939F1ZV")));
        assertThat(as(b, b.owner(), "OWNER", "GET", "/finance-gst/summary", null).status()).as("GST is not in the limited plan").isEqualTo(403);
        assertThat(as(b, b.owner(), "OWNER", "GET", "/finance-gst/export", null).status()).isEqualTo(403);
        assertThat(as(b, b.owner(), "OWNER", "POST", "/finance-alerts/evaluate", Map.of()).status()).as("alerts are in the limited plan").isEqualTo(200);
        // core finance is never gated
        assertThat(bget(b, "/finance-reports/business-performance").status()).isEqualTo(200);
        client(b, "Still allowed");
        assertThat(bpost(b, "/finance-subscription/change", Map.of("plan_key", "standard")).status()).isEqualTo(200);
        assertThat(bpost(b, "/finance-assistant/ask", Map.of("question", "How much did I spend last month?")).status()).as("back on standard").isEqualTo(200);
    }

    @Test
    void theDocumentLimitIsEnforcedOnTheServerAndExistingDocumentsAreKept() throws Exception {
        Biz b = biz();
        ok(bpost(b, "/finance-subscription/change", Map.of("plan_key", "limited")));
        for (int i = 0; i < 25; i++) {
            jdbc.sql("insert into finance_documents (id, tenant_id, domain, kind, original_filename, content_type, size_bytes, sha256, storage_key, created_by, created_at, updated_at) "
                    + "values (:id, :t, 'BUSINESS', 'OTHER', 'f.pdf', 'application/pdf', 10, :sha, 'k', :u, now(), now())").param("id", UUID.randomUUID()).param("t", b.space())
                    .param("sha", String.format("%064d", i)).param("u", b.owner()).update();
        }
        byte[] pdf = ("%PDF-1.4\n% " + UUID.randomUUID() + "\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1);
        Resp blocked = upload("/v1/spaces/" + b.space() + "/finance-documents?kind=OTHER&filename=x.pdf", btoken(b, b.owner(), "OWNER"), pdf, "application/octet-stream");
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.text("detail")).contains("allows up to 25 documents").contains("Existing records are unaffected");
        assertThat(count("select count(*) from finance_documents where tenant_id = :p0", b.space())).isEqualTo(25);
        assertThat(bpost(b, "/finance-subscription/change", Map.of("plan_key", "standard")).status()).isEqualTo(200);
        assertThat(upload("/v1/spaces/" + b.space() + "/finance-documents?kind=OTHER&filename=x.pdf", btoken(b, b.owner(), "OWNER"), pdf, "application/octet-stream").status()).isEqualTo(201);
    }

    @Test
    void cancellingBlocksPremiumFeaturesOnlyAndNeverHidesOrDeletesFinancialData() throws Exception {
        Biz b = biz();
        String c = client(b, "Kept Client");
        String inv = issued(b, c, "100.00");
        ok(expense(b, "10.00", "2026-03-01", null, null, null));
        Resp cancel = bpost(b, "/finance-subscription/cancel", Map.of("reason", "testing"));
        assertThat(cancel.status()).as(cancel.raw().body()).isEqualTo(200);
        assertThat(cancel.text("status")).isEqualTo("CANCELLED");
        assertThat(cancel.text("cancel_reason")).isEqualTo("testing");
        assertThat(cancel.body().path("notices").toString()).contains("cancelled").contains("financial data is kept");
        Resp ask = bpost(b, "/finance-assistant/ask", Map.of("question", "How much did I spend last month?"));
        assertThat(ask.status()).isEqualTo(403);
        assertThat(ask.text("detail")).contains("Your subscription is cancelled").contains("Your financial data is not affected");
        assertThat(bget(b, "/invoices/" + inv).status()).as("data stays readable").isEqualTo(200);
        assertThat(bget(b, "/expenses").body().path("total").asLong()).isEqualTo(1);
        assertThat(bget(b, "/finance-reports/business-performance").status()).isEqualTo(200);
        ok(expense(b, "5.00", "2026-03-02", null, null, null));
        assertThat(as(b, b.owner(), "OWNER", "POST", "/finance-alerts/evaluate", Map.of()).status()).isEqualTo(403);
        assertThat(bget(b, "/finance-alerts").status()).as("existing alerts stay readable").isEqualTo(200);
        assertThat(count("select count(*) from invoices where tenant_id = :p0", b.space())).isEqualTo(1);
        assertThat(bpost(b, "/finance-subscription/reactivate", Map.of()).text("status")).isEqualTo("ACTIVE");
        assertThat(bpost(b, "/finance-assistant/ask", Map.of("question", "How much did I spend last month?")).status()).isEqualTo(200);
    }

    @Test
    void invalidLifecycleMovesAreRefusedAndEveryChangeIsRecordedAndAudited() throws Exception {
        Biz b = biz();
        assertThat(bpost(b, "/finance-subscription/reactivate", Map.of()).code()).isEqualTo("INVALID_STATE_TRANSITION");
        ok(bpost(b, "/finance-subscription/cancel", Map.of()));
        clock.advance(java.time.Duration.ofSeconds(1));
        assertThat(bpost(b, "/finance-subscription/cancel", Map.of()).code()).as("cancelling twice").isEqualTo("INVALID_STATE_TRANSITION");
        Resp change = bpost(b, "/finance-subscription/change", Map.of("plan_key", "limited"));
        assertThat(change.status()).isEqualTo(409);
        assertThat(change.text("detail")).contains("only be changed while the subscription is active");
        ok(bpost(b, "/finance-subscription/reactivate", Map.of()));
        clock.advance(java.time.Duration.ofSeconds(1));
        assertThat(bpost(b, "/finance-subscription/change", Map.of("plan_key", "nope")).status()).isEqualTo(422);
        assertThat(bpost(b, "/finance-subscription/change", Map.of()).status()).isEqualTo(422);
        assertThat(bpost(b, "/finance-subscription/change", Map.of("plan_key", "standard")).status()).as("the same plan is a no-op").isEqualTo(200);
        clock.advance(java.time.Duration.ofSeconds(1));
        ok(bpost(b, "/finance-subscription/change", Map.of("plan_key", "limited")));
        Resp history = bget(b, "/finance-subscription/history");
        assertThat(history.body().path("total").asLong()).isEqualTo(3);
        assertThat(history.body().path("items").get(0).path("to_plan").asString()).isEqualTo("limited");
        assertThat(history.body().path("items").get(0).path("source").asString()).isEqualTo("OWNER");
        assertThat(bget(b, "/finance-subscription/history?limit=1&offset=2").body().path("items")).hasSize(1);
        assertThat(audit(b.space(), "SUBSCRIPTION_CANCELLED")).isEqualTo(1);
        assertThat(audit(b.space(), "SUBSCRIPTION_REACTIVATED")).isEqualTo(1);
        assertThat(audit(b.space(), "SUBSCRIPTION_PLAN_CHANGED")).isEqualTo(1);
        assertThat(count("select count(*) from notifications where tenant_id = :p0 and category = 'SUBSCRIPTION'", b.space())).isEqualTo(3);
    }

    @Test
    void aPlanThatRequiresPaymentCanNeverBeActivatedFromTheClient() throws Exception {
        Biz b = biz();
        owner().sql("insert into subscription_plans (plan_key, name, description, provisional, requires_payment) values ('paidtest', 'Paid test', 'arranged by the test', true, true)").update();
        owner().sql("insert into plan_entitlements (plan_key, entitlement_key, enabled, limit_value) values ('paidtest', 'assistant', true, null)").update();
        try {
            Resp r = bpost(b, "/finance-subscription/change", Map.of("plan_key", "paidtest"));
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.text("detail")).contains("requires a verified payment").contains("no billing provider is configured");
            assertThat(bget(b, "/finance-subscription").body().path("plan").path("plan_key").asString()).isEqualTo("standard");
            // only a verified payment event can activate it
            String rec = recordCreated(b, "100.00");
            Outcome paid = deliver(env("evt-" + UUID.randomUUID(), "billing.payment_succeeded", b.space(), clock.instant().getEpochSecond(),
                    Map.of("record_id", rec, "amount", "100.00", "currency", "INR", "provider_reference", "pay_ref_1", "plan_key", "paidtest")));
            assertThat(paid.status()).as(paid.detail()).isEqualTo("PROCESSED");
            Resp sub = bget(b, "/finance-subscription");
            assertThat(sub.body().path("plan").path("plan_key").asString()).isEqualTo("paidtest");
            assertThat(jdbc.sql("select source from subscription_events where tenant_id = :t order by created_at desc limit 1").param("t", b.space()).query(String.class).single()).isEqualTo("BILLING_EVENT");
            ok(bpost(b, "/finance-subscription/cancel", Map.of()));
            assertThat(bpost(b, "/finance-subscription/reactivate", Map.of()).status()).as("a paid plan cannot be reactivated without payment").isEqualTo(409);
        } finally {
            owner().sql("delete from workspace_subscriptions where plan_key = 'paidtest'").update();
            owner().sql("delete from plan_entitlements where plan_key = 'paidtest'").update();
            owner().sql("delete from subscription_plans where plan_key = 'paidtest'").update();
        }
    }

    @Test
    void onlyTheOwnerOfTheTokensWorkspaceCanChangeItsPlanAndNothingInTheRequestSelectsAnother() throws Exception {
        Biz a = biz();
        Biz other = biz();
        for (String role : List.of("ADMIN", "ACCOUNTANT", "MANAGER", "CONTRIBUTOR", "VIEWER", "NOT_A_ROLE")) {
            assertThat(as(a, UUID.randomUUID(), role, "POST", "/finance-subscription/change", Map.of("plan_key", "limited")).status()).as(role).isEqualTo(403);
            assertThat(as(a, UUID.randomUUID(), role, "POST", "/finance-subscription/cancel", Map.of()).status()).as(role).isEqualTo(403);
        }
        assertThat(bget(a, "/finance-subscription").body().path("plan").path("plan_key").asString()).isEqualTo("standard");
        assertThat(raw("POST", "/v1/spaces/" + a.space() + "/finance-subscription/change", btoken(other, other.owner(), "OWNER"), Map.of("plan_key", "limited")).status()).as("another workspace's owner").isEqualTo(404);
        Resp spoof = raw("POST", "/v1/spaces/" + other.space() + "/finance-subscription/change", btoken(other, other.owner(), "OWNER"), Map.of("plan_key", "limited", "workspace_id", a.space().toString(), "tenant_id", a.space().toString()));
        assertThat(spoof.status()).isEqualTo(200);
        assertThat(bget(a, "/finance-subscription").body().path("plan").path("plan_key").asString()).as("A is untouched").isEqualTo("standard");
        assertThat(bget(other, "/finance-subscription").body().path("plan").path("plan_key").asString()).isEqualTo("limited");
        for (String role : List.of("OWNER", "ADMIN", "ACCOUNTANT", "AUDITOR")) {
            assertThat(as(a, UUID.randomUUID(), role, "GET", "/finance-subscription", null).status()).as(role + " may view").isEqualTo(200);
        }
        for (String role : List.of("VIEWER", "CONTRIBUTOR")) {
            assertThat(as(a, UUID.randomUUID(), role, "GET", "/finance-subscription", null).status()).as(role).isEqualTo(403);
        }
        Person p = person();
        assertThat(post(p, "/finance-subscription/cancel", Map.of()).status()).isEqualTo(200);
        assertThat(raw("POST", base(p) + "/finance-subscription/change", token(p, p.space(), "ADMIN", List.of("workspace:personal")), Map.of("plan_key", "limited")).status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ billing contact and records

    @Test
    void billingIsHonestlyUnconfiguredAndTheContactIsValidatedAndOwnerOnly() throws Exception {
        Biz b = biz();
        Resp o = bget(b, "/finance-billing");
        assertThat(o.body().path("provider").path("status").asString()).isEqualTo("NOT_CONFIGURED");
        assertThat(o.body().path("payments_enabled").asBoolean()).isFalse();
        assertThat(o.text("message")).contains("No payment provider has been approved or configured").contains("no price applies");
        assertThat(o.body().path("notes").toString()).contains("not a tax invoice and not a payment receipt").contains("never marks anything paid");
        assertThat(bget(b, "/finance-billing/records").body().path("items")).isEmpty();
        Resp ok = as(b, b.owner(), "OWNER", "PUT", "/finance-billing/contact", Map.of("contact_name", "Finance Team", "contact_email", "billing@example.test", "card_number", "4242424242424242", "cvv", "123"));
        assertThat(ok.status()).as(ok.raw().body()).isEqualTo(200);
        assertThat(ok.raw().body()).doesNotContain("4242").doesNotContain("cvv");
        assertThat(jdbc.sql("select string_agg(column_name, ',') from information_schema.columns where table_name in ('billing_accounts', 'billing_records', 'payment_attempts', 'billing_events')").query(String.class).single())
                .doesNotContain("card").doesNotContain("cvv").doesNotContain("pan");
        for (String bad : List.of("not-an-email", "a@b", "x".repeat(250) + "@example.test")) {
            assertThat(as(b, b.owner(), "OWNER", "PUT", "/finance-billing/contact", Map.of("contact_email", bad)).status()).as(bad).isEqualTo(422);
        }
        assertThat(as(b, b.owner(), "OWNER", "PUT", "/finance-billing/contact", Map.of("contact_name", "x".repeat(121))).status()).isEqualTo(422);
        for (String role : List.of("ADMIN", "ACCOUNTANT", "VIEWER")) {
            assertThat(as(b, UUID.randomUUID(), role, "PUT", "/finance-billing/contact", Map.of("contact_name", "X")).status()).as(role).isEqualTo(403);
        }
        assertThat(bget(b, "/finance-billing").body().path("contact").path("contact_email").asString()).isEqualTo("billing@example.test");
        assertThat(audit(b.space(), "BILLING_CONTACT_UPDATED")).isEqualTo(1);
        assertThat(jdbc.sql("select string_agg(coalesce(detail, ''), ' ') from finance_audit_events where tenant_id = :t").param("t", b.space()).query(String.class).single()).doesNotContain("billing@example.test");
        Biz other = biz();
        assertThat(bget(other, "/finance-billing").body().path("contact").isEmpty()).isTrue();
        assertThat(as(other, UUID.randomUUID(), "ACCOUNTANT", "GET", "/finance-billing", null).status()).isIn(200, 403);
    }

    // ------------------------------------------------------------------ verified billing events

    @Test
    void aForgedOrReplayedWebhookIsRejectedAndChangesNothing() throws Exception {
        Biz b = biz();
        Map<String, Object> event = env("evt-forged", "billing.record_created", b.space(), clock.instant().getEpochSecond(), Map.of("record_id", UUID.randomUUID().toString(), "amount", "10.00", "currency", "INR", "description", "x"));
        byte[] body = json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        long ts = clock.instant().getEpochSecond();
        assertThat(processor.verifyAndProcess(SECRET, "testpay", String.valueOf(ts), body, "00".repeat(32)).status()).isEqualTo("REJECTED_SIGNATURE");
        assertThat(processor.verifyAndProcess("other-secret".getBytes(StandardCharsets.UTF_8), "testpay", String.valueOf(ts), body, WebhookVerifier.sign(SECRET, ts, body)).status()).isEqualTo("REJECTED_SIGNATURE");
        assertThat(processor.verifyAndProcess(SECRET, "testpay", String.valueOf(ts), "{\"tampered\":true}".getBytes(StandardCharsets.UTF_8), WebhookVerifier.sign(SECRET, ts, body)).status()).isEqualTo("REJECTED_SIGNATURE");
        long old = ts - 3600;
        assertThat(processor.verifyAndProcess(SECRET, "testpay", String.valueOf(old), body, WebhookVerifier.sign(SECRET, old, body)).status()).as("replay outside the window").isEqualTo("REJECTED_SIGNATURE");
        assertThat(processor.verifyAndProcess(SECRET, "testpay", null, body, WebhookVerifier.sign(SECRET, ts, body)).status()).isEqualTo("REJECTED_SIGNATURE");
        assertThat(processor.verifyAndProcess(SECRET, "testpay", String.valueOf(ts), body, null).status()).isEqualTo("REJECTED_SIGNATURE");
        assertThat(count("select count(*) from billing_records where tenant_id = :p0", b.space())).isZero();
        assertThat(count("select count(*) from billing_events where tenant_id = :p0", b.space())).isZero();
        assertThat(deliver(event).status()).isEqualTo("PROCESSED");
        assertThat(bget(b, "/finance-billing/records").body().path("total").asLong()).isEqualTo(1);
    }

    @Test
    void aDuplicateEventIsProcessedOnceAndRepeatsChangeNothing() throws Exception {
        Biz b = biz();
        String rec = recordCreated(b, "100.00");
        Map<String, Object> pay = env("evt-pay-1", "billing.payment_succeeded", b.space(), clock.instant().getEpochSecond(), Map.of("record_id", rec, "amount", "100.00", "currency", "INR", "provider_reference", "pay_1"));
        assertThat(deliver(pay).status()).isEqualTo("PROCESSED");
        for (int i = 0; i < 3; i++) {
            assertThat(deliver(pay).status()).isEqualTo("DUPLICATE");
        }
        assertThat(count("select count(*) from payment_attempts where tenant_id = :p0", b.space())).isEqualTo(1);
        assertThat(count("select count(*) from billing_events where tenant_id = :p0 and event_id like 'evt-pay-1-%'", b.space())).isEqualTo(1);
        var results = concurrently(List.of(() -> dupe(b, "evt-race"), () -> dupe(b, "evt-race"), () -> dupe(b, "evt-race")));
        assertThat(results.stream().mapToInt(Integer::intValue).sum()).as("exactly one concurrent delivery wins").isEqualTo(1);
        assertThat(count("select count(*) from billing_records where tenant_id = :p0", b.space())).isEqualTo(2);
    }

    private int dupe(Biz b, String id) throws Exception {
        return deliver(Map.of("id", id, "type", "billing.record_created", "workspace_id", b.space().toString(), "created", clock.instant().getEpochSecond(), "record_id", UUID.nameUUIDFromBytes((id + run).getBytes(StandardCharsets.UTF_8)).toString(),
                "amount", "1.00", "currency", "INR", "description", "race")).status().equals("PROCESSED") ? 1 : 0;
    }

    @Test
    void aRecordIsPaidOnlyByAVerifiedEventWithTheProvidersReferenceAndAMatchingAmount() throws Exception {
        Biz b = biz();
        String rec = recordCreated(b, "100.00");
        long t = clock.instant().getEpochSecond();
        assertThat(deliver(env("e1", "billing.payment_succeeded", b.space(), t, Map.of("record_id", rec, "amount", "100.00", "currency", "INR"))).status()).as("no provider reference").isEqualTo("REJECTED_INVALID");
        assertThat(deliver(env("e2", "billing.payment_succeeded", b.space(), t, Map.of("record_id", rec, "amount", "99.00", "currency", "INR", "provider_reference", "r"))).status()).as("wrong amount").isEqualTo("REJECTED_INVALID");
        assertThat(deliver(env("e3", "billing.payment_succeeded", b.space(), t, Map.of("record_id", rec, "amount", "100.00", "currency", "USD", "provider_reference", "r"))).status()).as("wrong currency").isEqualTo("REJECTED_INVALID");
        assertThat(count("select count(*) from billing_records where tenant_id = :p0 and status = 'PAID'", b.space())).isZero();
        Resp before = bget(b, "/finance-billing/records");
        assertThat(before.body().path("items").get(0).path("payment_confirmed").asBoolean()).isFalse();
        assertThat(before.body().path("items").get(0).path("kind").asString()).isEqualTo("APPLICATION_RECORD");
        assertThat(before.body().path("items").get(0).path("legal_tax_invoice").asBoolean()).isFalse();
        assertThat(deliver(env("e1", "billing.payment_succeeded", b.space(), t, Map.of("record_id", rec, "amount", "100.00", "currency", "INR", "provider_reference", "pay_ok"))).status())
                .as("a rejected event can be resent corrected under the same id").isEqualTo("PROCESSED");
        JsonNode paid = bget(b, "/finance-billing/records").body().path("items").get(0);
        assertThat(paid.path("status").asString()).isEqualTo("PAID");
        assertThat(paid.path("payment_confirmed").asBoolean()).isTrue();
        assertThat(paid.path("provider_reference").asString()).isEqualTo("pay_ok");
        assertThat(paid.path("payment_attempts")).hasSize(1);
        String open = recordCreated(b, "5.00");
        assertThatThrownBy(() -> jdbc.sql("update billing_records set status = 'PAID', paid_at = now() where tenant_id = :t and id = :id").param("t", b.space()).param("id", UUID.fromString(open)).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(deliver(env("e9", "billing.payment_succeeded", b.space(), t, Map.of("record_id", rec, "amount", "100.00", "currency", "INR", "provider_reference", "again"))).status()).as("already paid").isEqualTo("IGNORED_INVALID_STATE");
    }

    @Test
    void failuresDelayedEventsAndOutOfOrderEventsCannotUndoALaterState() throws Exception {
        Biz b = biz();
        String rec = recordCreated(b, "50.00");
        long t = clock.instant().getEpochSecond();
        assertThat(deliver(env("f2", "billing.payment_failed", b.space(), t + 100, Map.of("record_id", rec, "failure_code", "card_declined"))).status()).isEqualTo("PROCESSED");
        assertThat(bget(b, "/finance-subscription").text("status")).as("a verified failure makes the subscription past due").isEqualTo("PAST_DUE");
        assertThat(deliver(env("f1", "billing.payment_failed", b.space(), t + 10, Map.of("record_id", rec, "failure_code", "older"))).status()).as("an older event arriving late").isEqualTo("IGNORED_OUT_OF_ORDER");
        assertThat(deliver(env("fbad", "billing.payment_failed", b.space(), t + 200, Map.of("record_id", rec, "failure_code", "has spaces and <markup>"))).status()).isEqualTo("REJECTED_INVALID");
        Resp ask = bpost(b, "/finance-assistant/ask", Map.of("question", "How much did I spend last month?"));
        assertThat(ask.status()).isEqualTo(403);
        assertThat(ask.text("detail")).contains("past due").contains("financial data is not affected");
        assertThat(bget(b, "/finance-subscription").body().path("notices").toString()).contains("overdue");
        assertThat(bget(b, "/expenses").status()).as("financial data stays available while past due").isEqualTo(200);
        assertThat(bpost(b, "/finance-subscription/change", Map.of("plan_key", "limited")).status()).as("no plan change while past due").isEqualTo(409);
        assertThat(deliver(env("p1", "billing.payment_succeeded", b.space(), t + 300, Map.of("record_id", rec, "amount", "50.00", "currency", "INR", "provider_reference", "pay_late"))).status()).isEqualTo("PROCESSED");
        assertThat(bget(b, "/finance-subscription").text("status")).as("a verified payment restores the subscription").isEqualTo("ACTIVE");
        assertThat(deliver(env("f3", "billing.payment_failed", b.space(), t + 400, Map.of("record_id", rec, "failure_code", "late_failure"))).status()).as("a failure cannot undo a paid record").isEqualTo("IGNORED_INVALID_STATE");
        assertThat(bget(b, "/finance-subscription").text("status")).isEqualTo("ACTIVE");
        assertThat(count("select count(*) from notifications where tenant_id = :p0 and category = 'BILLING'", b.space())).isEqualTo(2);
        String voidRec = recordCreated(b, "1.00");
        assertThat(deliver(env("v1", "billing.record_voided", b.space(), t + 500, Map.of("record_id", voidRec))).status()).isEqualTo("PROCESSED");
        assertThat(deliver(env("v2", "billing.payment_succeeded", b.space(), t + 600, Map.of("record_id", voidRec, "amount", "1.00", "currency", "INR", "provider_reference", "x"))).status()).as("a void record cannot be paid").isEqualTo("IGNORED_INVALID_STATE");
    }

    @Test
    void aFailureNeverResurrectsACancelledSubscriptionAndEventsStayInsideTheirWorkspace() throws Exception {
        Biz a = biz();
        Biz other = biz();
        ok(bpost(a, "/finance-subscription/cancel", Map.of()));
        String rec = recordCreated(a, "10.00");
        long t = clock.instant().getEpochSecond();
        assertThat(deliver(env("c1", "billing.payment_failed", a.space(), t + 10, Map.of("record_id", rec, "failure_code", "declined"))).status()).isEqualTo("PROCESSED");
        assertThat(bget(a, "/finance-subscription").text("status")).as("a cancelled subscription is not moved to past due").isEqualTo("CANCELLED");
        // an event naming A's record but another workspace cannot touch A's record
        assertThat(deliver(env("c2", "billing.payment_succeeded", other.space(), t + 20, Map.of("record_id", rec, "amount", "10.00", "currency", "INR", "provider_reference", "x"))).status()).isEqualTo("IGNORED_INVALID_STATE");
        assertThat(bget(other, "/finance-billing/records").body().path("items")).isEmpty();
        assertThat(count("select count(*) from billing_records where tenant_id = :p0 and status = 'PAID'", a.space())).isZero();
        assertThat(bget(a, "/finance-billing/records").body().path("items").get(0).path("status").asString()).isEqualTo("PAYMENT_FAILED");
        assertThat(bget(other, "/finance-subscription").text("status")).isEqualTo("ACTIVE");
        // malformed events
        for (Map<String, Object> bad : List.of(Map.<String, Object>of("id", "x"), env("m1", "billing.unknown", a.space(), t, Map.of()), env("m2", "billing.record_created", "not-a-uuid", t, Map.of()),
                env("m3", "billing.record_created", a.space(), t, Map.of("record_id", UUID.randomUUID().toString(), "amount", "-1.00", "currency", "INR", "description", "x")),
                env("m4", "billing.record_created", a.space(), t, Map.of("record_id", UUID.randomUUID().toString(), "amount", "1.005", "currency", "INR", "description", "x")),
                env("m5", "billing.record_created", a.space(), t, Map.of("record_id", UUID.randomUUID().toString(), "amount", "1.00", "currency", "XXX", "description", "x")))) {
            assertThat(deliver(bad).status()).as(bad.toString()).isEqualTo("REJECTED_INVALID");
        }
        byte[] notJson = "not json".getBytes(StandardCharsets.UTF_8);
        long ts = clock.instant().getEpochSecond();
        assertThat(processor.verifyAndProcess(SECRET, "testpay", String.valueOf(ts), notJson, WebhookVerifier.sign(SECRET, ts, notJson)).status()).isEqualTo("REJECTED_INVALID");
        assertThat(processor.process("Bad Provider!", json.createObjectNode()).status()).isEqualTo("REJECTED_INVALID");
    }

    @Test
    void billingHistoryIsPagedAndLimitedToTheWorkspace() throws Exception {
        Biz b = biz();
        for (int i = 0; i < 5; i++) {
            recordCreated(b, i + ".00");
        }
        Resp p = bget(b, "/finance-billing/records?limit=2&offset=0");
        assertThat(p.body().path("total").asLong()).isEqualTo(5);
        assertThat(p.body().path("items")).hasSize(2);
        assertThat(bget(b, "/finance-billing/records?limit=2&offset=4").body().path("items")).hasSize(1);
        assertThat(as(b, UUID.randomUUID(), "VIEWER", "GET", "/finance-billing/records", null).status()).isEqualTo(403);
        assertThat(as(b, UUID.randomUUID(), "CONTRIBUTOR", "GET", "/finance-billing", null).status()).isEqualTo(403);
        assertThat(raw("GET", "/v1/spaces/" + b.space() + "/finance-billing/records", btoken(biz(), UUID.randomUUID(), "OWNER"), null).status()).isEqualTo(404);
    }
}
