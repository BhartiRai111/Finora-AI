package com.finora.finance.subscriptions;

import com.finora.commons.error.ApiException;
import com.finora.finance.accounting.WebhookVerifier;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only way billing records are created, paid or failed, and the only way a paid plan or PAST_DUE/EXPIRED state is set. It accepts a provider-neutral
 * event envelope (this is Finora's own contract, not any provider's API) and only after the signature is verified. No payment provider is approved and no
 * HTTP webhook route is exposed, so in production this is unused; it exists so the rules can be tested without any provider or credential:
 * forged and replayed events are rejected, duplicates are ignored, out-of-order events cannot undo a later state, and a record can only be PAID with the provider's
 * own reference and a matching amount. Raw payloads are never stored or logged.
 */
@Service
public class BillingEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(BillingEventProcessor.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern PROVIDER = Pattern.compile("^[a-z][a-z0-9_-]{1,30}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_.-]{1,40}$");
    static final Duration TOLERANCE = Duration.ofMinutes(5);

    /** status: PROCESSED, DUPLICATE, IGNORED_OUT_OF_ORDER, IGNORED_INVALID_STATE, REJECTED_SIGNATURE, REJECTED_INVALID. */
    public record Outcome(String status, String detail) {
    }

    private record Record(String status, String currency, BigDecimal amount, OffsetDateTime lastEventAt) {
    }

    private final JdbcClient jdbc;
    private final SubscriptionService subscriptions;
    private final com.finora.finance.notifications.NotificationService notifications;
    private final Clock clock;
    private final TransactionTemplate tx;

    public BillingEventProcessor(JdbcClient jdbc, SubscriptionService subscriptions, com.finora.finance.notifications.NotificationService notifications, Clock clock,
            PlatformTransactionManager manager) {
        this.tx = new TransactionTemplate(manager);
        this.jdbc = jdbc;
        this.subscriptions = subscriptions;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Verifies the HMAC signature and replay window first; nothing is parsed or stored for an event that fails. */
    public Outcome verifyAndProcess(byte[] secret, String provider, String timestampHeader, byte[] body, String signatureHex) {
        if (!WebhookVerifier.verify(secret, timestampHeader, body, signatureHex, clock.instant(), TOLERANCE)) {
            log.warn("Billing webhook rejected: signature or timestamp invalid");
            return new Outcome("REJECTED_SIGNATURE", "The signature or timestamp is not valid");
        }
        JsonNode n;
        try {
            n = JSON.readTree(body);
        } catch (RuntimeException e) {
            return new Outcome("REJECTED_INVALID", "The event is not valid JSON");
        }
        return process(provider, n);
    }

    /** One event, one transaction: a rejected event rolls back everything, including its dedupe row, so a corrected resend can still be processed. */
    public Outcome process(String provider, JsonNode n) {
        return tx.execute(status -> {
            Outcome out;
            try {
                out = processInTransaction(provider, n);
            } catch (ApiException e) {
                status.setRollbackOnly(); // nothing the event started is kept
                return new Outcome("IGNORED_INVALID_STATE", e.getMessage() == null ? "Not allowed in the current state" : e.getMessage());
            }
            if (out.status().startsWith("REJECTED")) {
                status.setRollbackOnly();
            }
            return out;
        });
    }

    private Outcome processInTransaction(String provider, JsonNode n) {
        if (provider == null || !PROVIDER.matcher(provider).matches() || n == null || !n.isObject()) {
            return new Outcome("REJECTED_INVALID", "Invalid event");
        }
        String eventId = text(n, "id");
        String type = text(n, "type");
        UUID tenant = uuid(text(n, "workspace_id"));
        if (eventId == null || eventId.length() > 100 || type == null || tenant == null || !n.path("created").isIntegralNumber()) {
            return new Outcome("REJECTED_INVALID", "The event is missing required fields");
        }
        OffsetDateTime eventAt = OffsetDateTime.ofInstant(Instant.ofEpochSecond(n.path("created").asLong()), ZoneOffset.UTC);
        if (jdbc.sql("insert into billing_events (provider, event_id, tenant_id, event_type, event_at, outcome, received_at) values (:p, :e, :t, :ty, :at, 'PENDING', :now) on conflict do nothing")
                .param("p", provider).param("e", eventId).param("t", tenant).param("ty", type).param("at", eventAt).param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update() == 0) {
            return new Outcome("DUPLICATE", "This event was already received");
        }
        Outcome out = switch (type) {
            case "billing.record_created" -> created(provider, tenant, n, eventAt);
            case "billing.payment_succeeded" -> succeeded(provider, tenant, n, eventAt);
            case "billing.payment_failed" -> failed(provider, tenant, n, eventAt);
            case "billing.record_voided" -> voided(tenant, n, eventAt);
            default -> new Outcome("REJECTED_INVALID", "Unknown event type");
        };
        if (out.status().startsWith("REJECTED")) {
            return out;
        }
        jdbc.sql("update billing_events set outcome = :o where provider = :p and event_id = :e").param("o", out.status()).param("p", provider).param("e", eventId).update();
        return out;
    }

    private static String text(JsonNode n, String f) {
        return n.path(f).isString() && !n.path(f).asString().isBlank() ? n.path(f).asString().trim() : null;
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Record record(UUID tenant, UUID id) {
        return jdbc.sql("select status, currency, amount, last_event_at from billing_records where tenant_id = :t and id = :id for update").param("t", tenant).param("id", id).query((rs, i) ->
                new Record(rs.getString(1), rs.getString(2).trim(), rs.getBigDecimal(3), rs.getObject(4, OffsetDateTime.class))).optional().orElse(null);
    }

    private Outcome created(String provider, UUID tenant, JsonNode n, OffsetDateTime at) {
        UUID id = uuid(text(n, "record_id"));
        String currency = text(n, "currency");
        String description = text(n, "description");
        if (id == null || currency == null || !Currencies.supported(currency) || description == null || description.length() > 200 || !n.path("amount").isString()) {
            return new Outcome("REJECTED_INVALID", "A billing record needs record_id, a supported currency, an amount and a description");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(n.path("amount").asString());
        } catch (NumberFormatException e) {
            return new Outcome("REJECTED_INVALID", "amount must be a decimal string");
        }
        if (amount.signum() < 0 || amount.scale() > Currencies.exponentOrThrow(currency)) {
            return new Outcome("REJECTED_INVALID", "amount is not valid for the currency");
        }
        jdbc.sql("""
                insert into billing_records (id, tenant_id, status, currency, amount, description, period_end, last_event_at, created_at, updated_at)
                values (:id, :t, 'OPEN', :c, :a, :d, :pe, :at, :now, :now) on conflict do nothing
                """).param("id", id).param("t", tenant).param("c", currency).param("a", amount).param("d", description).param("pe", periodEnd(n)).param("at", at)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update();
        return new Outcome("PROCESSED", "Billing record created");
    }

    private static LocalDate periodEnd(JsonNode n) {
        try {
            return n.path("period_end").isString() ? LocalDate.parse(n.path("period_end").asString()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Outcome succeeded(String provider, UUID tenant, JsonNode n, OffsetDateTime at) {
        UUID id = uuid(text(n, "record_id"));
        String ref = text(n, "provider_reference");
        if (id == null || ref == null || ref.length() > 100) {
            return new Outcome("REJECTED_INVALID", "A payment needs record_id and the provider's own reference");
        }
        Record r = record(tenant, id);
        if (r == null) {
            return new Outcome("IGNORED_INVALID_STATE", "Unknown billing record");
        }
        if ("PAID".equals(r.status())) {
            return new Outcome("IGNORED_INVALID_STATE", "Already paid");
        }
        if ("VOID".equals(r.status())) {
            return new Outcome("IGNORED_INVALID_STATE", "The record is void");
        }
        if (!r.currency().equals(text(n, "currency")) || !n.path("amount").isString() || new BigDecimal(n.path("amount").asString()).compareTo(r.amount()) != 0) {
            return new Outcome("REJECTED_INVALID", "The paid amount or currency does not match the billing record");
        }
        jdbc.sql("update billing_records set status = 'PAID', provider = :p, provider_reference = :ref, paid_at = :at, last_event_at = greatest(coalesce(last_event_at, :at), :at), updated_at = :now where tenant_id = :t and id = :id")
                .param("p", provider).param("ref", ref).param("at", at).param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).param("t", tenant).param("id", id).update();
        attempt(tenant, id, "SUCCEEDED", provider, ref, null, at);
        String plan = text(n, "plan_key");
        if (plan != null) {
            subscriptions.applyVerifiedBilling(tenant, clock, "ACTIVE", plan, periodEnd(n), "verified payment");
        } else {
            subscriptions.applyVerifiedBilling(tenant, clock, "ACTIVE", null, periodEnd(n), "verified payment");
        }
        notifications.create(tenant, clock, null, "SETTINGS", "BILLING", "INFO", "Payment confirmed", "A payment for a billing record was confirmed by the payment provider.", Map.of("record", id.toString()), "billing:paid:" + id);
        return new Outcome("PROCESSED", "Payment recorded");
    }

    private Outcome failed(String provider, UUID tenant, JsonNode n, OffsetDateTime at) {
        UUID id = uuid(text(n, "record_id"));
        String code = text(n, "failure_code");
        if (id == null || (code != null && !CODE.matcher(code).matches())) {
            return new Outcome("REJECTED_INVALID", "A failed payment needs record_id and a short failure_code");
        }
        Record r = record(tenant, id);
        if (r == null) {
            return new Outcome("IGNORED_INVALID_STATE", "Unknown billing record");
        }
        if ("PAID".equals(r.status()) || "VOID".equals(r.status())) {
            return new Outcome("IGNORED_INVALID_STATE", "The record is " + r.status().toLowerCase(java.util.Locale.ROOT) + "; a failure cannot change it");
        }
        if (r.lastEventAt() != null && at.isBefore(r.lastEventAt())) {
            return new Outcome("IGNORED_OUT_OF_ORDER", "A later event for this record was already processed");
        }
        jdbc.sql("update billing_records set status = 'PAYMENT_FAILED', last_event_at = :at, updated_at = :now where tenant_id = :t and id = :id").param("at", at)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).param("t", tenant).param("id", id).update();
        attempt(tenant, id, "FAILED", provider, null, code, at);
        subscriptions.applyVerifiedBilling(tenant, clock, "PAST_DUE", null, null, "payment failed"); // a cancelled or expired subscription simply stays as it is
        notifications.create(tenant, clock, null, "SETTINGS", "BILLING", "WARNING", "Payment failed", "A payment attempt failed. Your financial data is not affected.", Map.of("record", id.toString()), "billing:failed:" + id + ":" + at.toEpochSecond());
        return new Outcome("PROCESSED", "Payment failure recorded");
    }

    private Outcome voided(UUID tenant, JsonNode n, OffsetDateTime at) {
        UUID id = uuid(text(n, "record_id"));
        if (id == null) {
            return new Outcome("REJECTED_INVALID", "record_id is required");
        }
        Record r = record(tenant, id);
        if (r == null || "PAID".equals(r.status()) || "VOID".equals(r.status())) {
            return new Outcome("IGNORED_INVALID_STATE", "The record cannot be voided in its current state");
        }
        jdbc.sql("update billing_records set status = 'VOID', last_event_at = greatest(coalesce(last_event_at, :at), :at), updated_at = :now where tenant_id = :t and id = :id").param("at", at)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).param("t", tenant).param("id", id).update();
        return new Outcome("PROCESSED", "Record voided");
    }

    private void attempt(UUID tenant, UUID record, String status, String provider, String ref, String code, OffsetDateTime at) {
        jdbc.sql("insert into payment_attempts (id, tenant_id, billing_record_id, status, provider, provider_reference, failure_code, event_at, created_at) values (:id, :t, :r, :s, :p, :ref, :c, :at, :now)")
                .param("id", UUID.randomUUID()).param("t", tenant).param("r", record).param("s", status).param("p", provider).param("ref", ref).param("c", code).param("at", at)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update();
    }

    public static List<String> types() {
        return List.of("billing.record_created", "billing.payment_succeeded", "billing.payment_failed", "billing.record_voided");
    }
}
