package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Harness for business-finance tests: one random business workspace (tenant) per test, callers with any role. */
public abstract class BusinessTestSupport extends FinanceTestSupport {

    /** A business workspace and its owner. */
    public record Biz(UUID space, UUID owner) {
    }

    protected Biz biz() {
        return new Biz(UUID.randomUUID(), UUID.randomUUID());
    }

    protected String btoken(Biz b, UUID user, String role) {
        return token(new Person(user, b.space()), b.space(), role, List.of("workspace:business"));
    }

    protected Resp as(Biz b, UUID user, String role, String method, String suffix, Object body, String... headers) throws Exception {
        return raw(method, "/v1/spaces/" + b.space() + suffix, btoken(b, user, role), body, headers);
    }

    protected Resp bget(Biz b, String suffix) throws Exception {
        return as(b, b.owner(), "OWNER", "GET", suffix, null);
    }

    protected Resp bpost(Biz b, String suffix, Object body) throws Exception {
        return as(b, b.owner(), "OWNER", "POST", suffix, body, "Idempotency-Key", UUID.randomUUID().toString());
    }

    protected Resp bpostKeyed(Biz b, String suffix, Object body, String key) throws Exception {
        return as(b, b.owner(), "OWNER", "POST", suffix, body, "Idempotency-Key", key);
    }

    protected Resp bpatch(Biz b, String suffix, Object body) throws Exception {
        return as(b, b.owner(), "OWNER", "PATCH", suffix, body);
    }

    protected Resp bput(Biz b, String suffix, Object body) throws Exception {
        return as(b, b.owner(), "OWNER", "PUT", suffix, body);
    }

    // ---- fixtures ----

    protected String client(Biz b, String name) throws Exception {
        return ok(bpost(b, "/clients", Map.of("name", name)));
    }

    protected String vendor(Biz b, String name) throws Exception {
        return ok(bpost(b, "/vendors", Map.of("name", name)));
    }

    protected String project(Biz b, String name, String client, String currency, String budget) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("currency", currency);
        if (client != null) {
            body.put("client_id", client);
        }
        if (budget != null) {
            body.put("budget_amount", budget);
        }
        return ok(bpost(b, "/projects", body));
    }

    protected Map<String, Object> line(String description, String qty, String price) {
        return line(description, qty, price, "0", "0");
    }

    protected Map<String, Object> line(String description, String qty, String price, String discount, String taxPercent) {
        return Map.of("description", description, "quantity", qty, "unit_price", price, "discount", discount, "tax_percent", taxPercent);
    }

    protected Resp draft(Biz b, String client, String invoiceDate, String dueDate, String currency, List<Map<String, Object>> lines, String project) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", client);
        body.put("invoice_date", invoiceDate);
        if (dueDate != null) {
            body.put("due_date", dueDate);
        }
        body.put("currency", currency);
        body.put("lines", lines);
        if (project != null) {
            body.put("project_id", project);
        }
        return bpost(b, "/invoices", body);
    }

    protected String draftInvoice(Biz b, String client, String amount) throws Exception {
        return ok(draft(b, client, "2026-03-01", "2026-03-31", "INR", List.of(line("Work", "1", amount)), null));
    }

    protected Resp issue(Biz b, String id, String expectedTotal) throws Exception {
        return bpost(b, "/invoices/" + id + "/issue", Map.of("confirm", true, "expected_total", expectedTotal));
    }

    /** Draft + issue: returns the invoice id. */
    protected String issued(Biz b, String client, String amount) throws Exception {
        String id = draftInvoice(b, client, amount);
        ok(issue(b, id, amount));
        return id;
    }

    protected Resp pay(Biz b, String client, String amount, String reference, List<Map<String, Object>> allocations) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", client);
        body.put("payment_date", "2026-03-10");
        body.put("amount", amount);
        body.put("currency", "INR");
        body.put("method", "BANK_TRANSFER");
        if (reference != null) {
            body.put("reference", reference);
        }
        if (allocations != null) {
            body.put("allocations", allocations);
        }
        return bpost(b, "/payments", body);
    }

    protected Map<String, Object> alloc(String invoice, String amount) {
        return Map.of("invoice_id", invoice, "amount", amount);
    }

    protected Resp expense(Biz b, String amount, String date, String vendor, String project, String category) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expense_date", date);
        body.put("amount", amount);
        body.put("currency", "INR");
        body.put("description", "Spend");
        if (vendor != null) {
            body.put("vendor_id", vendor);
        }
        if (project != null) {
            body.put("project_id", project);
        }
        if (category != null) {
            body.put("category_id", category);
        }
        return bpost(b, "/expenses", body);
    }

    protected String status(Biz b, String invoice) throws Exception {
        return bget(b, "/invoices/" + invoice).text("display_status");
    }

    protected String outstanding(Biz b, String invoice) throws Exception {
        return bget(b, "/invoices/" + invoice).text("outstanding");
    }

    protected void assertCode(Resp r, int status, String code) {
        assertThat(r.status()).as(r.raw().body()).isEqualTo(status);
        assertThat(r.code()).as(r.raw().body()).isEqualTo(code);
    }
}
