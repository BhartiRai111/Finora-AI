package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Clients, vendors, projects and expenses: CRUD, validation, links, optimistic versions, archive rules. */
class PartiesProjectsExpensesTest extends BusinessTestSupport {

    @Test
    void clientsAreCreatedReadUpdatedAndArchivedWithAuditedChanges() throws Exception {
        Biz b = biz();
        Resp created = bpost(b, "/clients", Map.of("name", "Acme Traders", "email", "ap@acme.example", "gstin", "27aapfu0939f1zv", "payment_terms_days", 15, "address", "Pune"));
        assertThat(created.status()).as(created.raw().body()).isEqualTo(201);
        String id = created.id();
        assertThat(created.text("gstin")).isEqualTo("27AAPFU0939F1ZV");
        assertThat(created.body().path("payment_terms_days").asInt()).isEqualTo(15);
        assertThat(created.body().path("balances")).isEmpty();

        Resp updated = bpatch(b, "/clients/" + id, Map.of("version", 0, "name", "Acme Traders Pvt", "phone", "+91 98765 43210"));
        assertThat(updated.status()).as(updated.raw().body()).isEqualTo(200);
        assertThat(updated.text("name")).isEqualTo("Acme Traders Pvt");
        assertThat(updated.text("email")).isEqualTo("ap@acme.example");
        assertThat(updated.body().path("version").asInt()).isEqualTo(1);
        assertCode(bpatch(b, "/clients/" + id, Map.of("version", 0, "name", "Stale")), 409, "CONFLICT");
        Resp cleared = bpatch(b, "/clients/" + id, Map.of("version", 1, "address", ""));
        assertThat(cleared.body().path("address").isNull()).isTrue();
        assertThat(count("select count(*) from finance_audit_events where tenant_id = :p0 and event_type like 'CLIENT_%'", b.space())).isEqualTo(3);

        ok(bpost(b, "/clients/" + id + "/archive", Map.of()));
        assertThat(bget(b, "/clients").body().path("items")).isEmpty();
        assertThat(bget(b, "/clients?include_archived=true").body().path("items")).hasSize(1);
        assertCode(bpatch(b, "/clients/" + id, Map.of("version", 3, "name", "x")), 409, "CONFLICT");
        ok(bpost(b, "/clients/" + id + "/restore", Map.of()));
        assertThat(bget(b, "/clients").body().path("items")).hasSize(1);
    }

    @Test
    void duplicateNamesPartyValidationAndSearchBehave() throws Exception {
        Biz b = biz();
        client(b, "Acme");
        assertCode(bpost(b, "/clients", Map.of("name", "ACME")), 409, "CONFLICT");
        vendor(b, "Acme"); // clients and vendors are separate namespaces
        assertThat(bpost(b, "/clients", Map.of("name", "")).status()).isEqualTo(422);
        assertThat(bpost(b, "/clients", Map.of("name", "X", "email", "not-an-email")).status()).isEqualTo(422);
        assertThat(bpost(b, "/clients", Map.of("name", "X", "gstin", "12345")).status()).isEqualTo(422);
        assertThat(bpost(b, "/clients", Map.of("name", "X", "gstin", "99AAAAA0000A1Z5")).status()).as("state code above 39").isEqualTo(422);
        assertThat(bpost(b, "/clients", Map.of("name", "X", "payment_terms_days", 366)).status()).isEqualTo(422);
        assertThat(bpost(b, "/clients", Map.of("name", "A".repeat(121))).status()).isEqualTo(422);
        for (int i = 0; i < 5; i++) {
            client(b, "Client " + i);
        }
        Resp page = bget(b, "/clients?limit=2&offset=2");
        assertThat(page.body().path("total").asLong()).isEqualTo(6);
        assertThat(page.body().path("items")).hasSize(2);
        assertThat(bget(b, "/clients?q=client%203").body().path("total").asLong()).isEqualTo(1);
        assertThat(bget(b, "/clients?q=%25").body().path("total").asLong()).isZero();
        assertThat(bget(b, "/clients?q=_").body().path("total").asLong()).isZero();
    }

    @Test
    void aClientDetailShowsBalancesPerCurrencyAndBlocksArchivingWhileInvoicesAreUnpaid() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String i1 = issued(b, c, "100.00");
        issued(b, c, "50.00");
        ok(pay(b, c, "30.00", "R1", List.of(alloc(i1, "30.00"))));
        Resp d = bget(b, "/clients/" + c);
        assertThat(d.body().path("balances")).hasSize(1);
        assertThat(d.body().path("balances").get(0).path("invoiced").asString()).isEqualTo("150.00");
        assertThat(d.body().path("balances").get(0).path("paid").asString()).isEqualTo("30.00");
        assertThat(d.body().path("balances").get(0).path("outstanding").asString()).isEqualTo("120.00");
        assertThat(d.body().path("invoice_count").asLong()).isEqualTo(2);
        assertCode(bpost(b, "/clients/" + c + "/archive", Map.of()), 409, "CONFLICT");
    }

    @Test
    void vendorsTrackTheirExpenseTotalsPerCurrency() throws Exception {
        Biz b = biz();
        String v = vendor(b, "Stationers");
        ok(expense(b, "100.50", "2026-03-01", v, null, null));
        ok(expense(b, "20.25", "2026-03-02", v, null, null));
        Resp voided = expense(b, "999.00", "2026-03-03", v, null, null);
        ok(bpost(b, "/expenses/" + voided.id() + "/void", Map.of("reason", "mistake")));
        Resp d = bget(b, "/vendors/" + v);
        assertThat(d.body().path("expenses")).hasSize(1);
        assertThat(d.body().path("expenses").get(0).path("amount").asString()).isEqualTo("120.75");
        assertThat(bpatch(b, "/vendors/" + v, Map.of("version", 0, "name", "Stationers Ltd")).text("name")).isEqualTo("Stationers Ltd");
    }

    @Test
    void projectsKeepTheirCurrencyClientAndBudgetConsistent() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String p = project(b, "Warehouse", c, "INR", "10000.00");
        Resp d = bget(b, "/projects/" + p);
        assertThat(d.text("budget_amount")).isEqualTo("10000.00");
        assertThat(d.text("client_name")).isEqualTo("Acme");
        assertThat(d.body().path("profitability").path("costs").asString()).isEqualTo("0.00");
        assertCode(bpost(b, "/projects", Map.of("name", "warehouse", "currency", "INR")), 409, "CONFLICT");
        assertThat(bpost(b, "/projects", Map.of("name", "P2", "currency", "INR", "budget_amount", "0")).status()).isEqualTo(422);
        assertThat(bpost(b, "/projects", Map.of("name", "P2", "currency", "INR", "budget_amount", "1.001")).status()).isEqualTo(422);
        assertThat(bpost(b, "/projects", Map.of("name", "P2", "currency", "INR", "start_date", "2026-03-10", "end_date", "2026-03-09")).status()).isEqualTo(422);
        assertThat(bpost(b, "/projects", Map.of("name", "P2", "currency", "INR", "status", "WEIRD")).status()).isEqualTo(422);
        assertThat(bpost(b, "/projects", Map.of("name", "P2", "currency", "INR", "client_id", UUID.randomUUID().toString())).status()).isEqualTo(422);

        // once something is linked, the currency and client are fixed
        ok(expense(b, "10.00", "2026-03-01", null, p, null));
        assertCode(bpatch(b, "/projects/" + p, Map.of("version", 0, "currency", "USD")), 409, "CONFLICT");
        String c2 = client(b, "Other");
        ok(draft(b, c, "2026-03-01", null, "INR", List.of(line("A", "1", "1")), p));
        assertCode(bpatch(b, "/projects/" + p, Map.of("version", 0, "client_id", c2)), 409, "CONFLICT");
        assertThat(bpatch(b, "/projects/" + p, Map.of("version", 0, "status", "ON_HOLD", "budget_amount", "12000.00")).text("status")).isEqualTo("ON_HOLD");
        assertThat(bpatch(b, "/projects/" + p, Map.of("version", 1, "clear_budget", true)).body().path("budget_amount").isNull()).isTrue();
        assertThat(bget(b, "/projects?status=ON_HOLD").body().path("total").asLong()).isEqualTo(1);
        assertThat(bget(b, "/projects?client_id=" + c).body().path("total").asLong()).isEqualTo(1);
        assertThat(bget(b, "/projects?status=NOPE").status()).isEqualTo(422);
        ok(bpost(b, "/projects/" + p + "/archive", Map.of()));
        assertThat(bget(b, "/projects").body().path("items")).isEmpty();
        assertThat(expense(b, "1.00", "2026-03-01", null, p, null).status()).as("archived project").isEqualTo(409);
    }

    @Test
    void expensesLinkVendorsProjectsAndCategoriesAndAreCheckedAtTheBoundaries() throws Exception {
        Biz b = biz();
        String v = vendor(b, "Cement Co");
        String p = project(b, "Site", null, "INR", null);
        String cat = ok(bpost(b, "/expense-categories", Map.of("name", "Materials")));
        Resp r = expense(b, "2500.75", "2026-03-15", v, p, cat);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(201);
        assertThat(r.text("vendor_name")).isEqualTo("Cement Co");
        assertThat(r.text("project_name")).isEqualTo("Site");
        assertThat(r.text("category_name")).isEqualTo("Materials");
        assertThat(r.text("amount")).isEqualTo("2500.75");

        // today in the business time zone is accepted, tomorrow is not
        assertThat(expense(b, "1.00", "2026-03-16", null, null, null).status()).isEqualTo(422);
        clock.set("2026-03-15T20:00:00Z");
        assertThat(expense(b, "1.00", "2026-03-16", null, null, null).status()).isEqualTo(201);
        for (String amount : List.of("0", "-1", "1.005", "abc", "1e3")) {
            assertThat(expense(b, amount, "2026-03-01", null, null, null).status()).as(amount).isEqualTo(422);
        }
        assertThat(expense(b, "1.00", "2026-03-01", UUID.randomUUID().toString(), null, null).status()).isEqualTo(422);
        assertThat(expense(b, "1.00", "2026-03-01", null, UUID.randomUUID().toString(), null).status()).isEqualTo(422);
        assertThat(expense(b, "1.00", "2026-03-01", null, null, UUID.randomUUID().toString()).status()).isEqualTo(422);

        // a project's currency is law: nothing is converted
        String usd = project(b, "Export", null, "USD", null);
        assertThat(expense(b, "1.00", "2026-03-01", null, usd, null).status()).isEqualTo(422);
        ok(bpatch(b, "/projects/" + p, Map.of("version", 0, "status", "COMPLETED")));
        assertThat(expense(b, "1.00", "2026-03-01", null, p, null).status()).as("completed project").isEqualTo(409);
        ok(bpost(b, "/vendors/" + v + "/archive", Map.of()));
        assertThat(expense(b, "1.00", "2026-03-01", v, null, null).status()).as("archived vendor").isEqualTo(422);
    }

    @Test
    void expenseEditsAreVersionedAuditedAndRevalidated() throws Exception {
        Biz b = biz();
        String p1 = project(b, "One", null, "INR", null);
        String p2 = project(b, "Two", null, "INR", null);
        String id = ok(expense(b, "100.00", "2026-03-01", null, p1, null));
        Resp moved = bpatch(b, "/expenses/" + id, Map.of("version", 0, "project_id", p2, "amount", "120.00", "description", "Updated"));
        assertThat(moved.status()).as(moved.raw().body()).isEqualTo(200);
        assertThat(moved.text("project_name")).isEqualTo("Two");
        assertThat(moved.text("amount")).isEqualTo("120.00");
        assertCode(bpatch(b, "/expenses/" + id, Map.of("version", 0, "amount", "1.00")), 409, "CONFLICT");
        assertThat(bpatch(b, "/expenses/" + id, Map.of("version", 1, "amount", "1.234")).status()).isEqualTo(422);
        assertThat(bpatch(b, "/expenses/" + id, Map.of("version", 1, "currency", "USD")).status()).as("currency fixed").isEqualTo(422);
        assertThat(bpatch(b, "/expenses/" + id, Map.of("version", 1, "clear_project", true)).body().path("project_id").isNull()).isTrue();
        assertThat(count("select count(*) from finance_audit_events where tenant_id = :p0 and event_type = 'EXPENSE_UPDATED' and detail like '%amount 100.00->120.00%'", b.space())).isEqualTo(1);

        ok(bpost(b, "/expenses/" + id + "/void", Map.of("reason", "Duplicate")));
        assertCode(bpatch(b, "/expenses/" + id, Map.of("version", 3, "amount", "5.00")), 409, "CONFLICT");
        assertCode(bpost(b, "/expenses/" + id + "/void", Map.of("reason", "again")), 409, "INVALID_STATE_TRANSITION");
    }

    @Test
    void theSameVendorBillCannotBePostedTwiceUntilTheFirstIsVoided() throws Exception {
        Biz b = biz();
        String v = vendor(b, "Cement Co");
        Map<String, Object> body = Map.of("expense_date", "2026-03-01", "amount", "10.00", "currency", "INR", "description", "Bill", "vendor_id", v, "reference", "BILL-9");
        Resp first = bpost(b, "/expenses", body);
        assertThat(first.status()).isEqualTo(201);
        assertCode(bpost(b, "/expenses", body), 409, "CONFLICT");
        ok(bpost(b, "/expenses/" + first.id() + "/void", Map.of("reason", "wrong")));
        assertThat(bpost(b, "/expenses", body).status()).isEqualTo(201);
        // a retry with the same Idempotency-Key replays instead of conflicting
        String key = UUID.randomUUID().toString();
        Map<String, Object> other = Map.of("expense_date", "2026-03-01", "amount", "5.00", "currency", "INR", "description", "Bill 2", "vendor_id", v, "reference", "BILL-10");
        Resp a = bpostKeyed(b, "/expenses", other, key);
        Resp again = bpostKeyed(b, "/expenses", other, key);
        assertThat(a.status()).isEqualTo(201);
        assertThat(again.id()).isEqualTo(a.id());
        assertThat(count("select count(*) from business_expenses where tenant_id = :p0 and status = 'POSTED'", b.space())).isEqualTo(2);
    }

    @Test
    void expenseListsFilterSearchAndPage() throws Exception {
        Biz b = biz();
        String v = vendor(b, "Cement Co");
        String p = project(b, "Site", null, "INR", null);
        String cat = ok(bpost(b, "/expense-categories", Map.of("name", "Materials")));
        ok(expense(b, "10.00", "2026-03-01", v, p, cat));
        ok(expense(b, "20.00", "2026-03-05", null, p, null));
        ok(expense(b, "30.00", "2026-03-09", v, null, null));
        String gone = ok(expense(b, "40.00", "2026-03-10", null, null, null));
        ok(bpost(b, "/expenses/" + gone + "/void", Map.of("reason", "x")));
        assertThat(bget(b, "/expenses").body().path("total").asLong()).isEqualTo(3);
        assertThat(bget(b, "/expenses?include_voided=true").body().path("total").asLong()).isEqualTo(4);
        assertThat(bget(b, "/expenses?project_id=" + p).body().path("total").asLong()).isEqualTo(2);
        assertThat(bget(b, "/expenses?vendor_id=" + v).body().path("total").asLong()).isEqualTo(2);
        assertThat(bget(b, "/expenses?category_id=" + cat).body().path("total").asLong()).isEqualTo(1);
        assertThat(bget(b, "/expenses?from=2026-03-05&to=2026-03-09").body().path("total").asLong()).isEqualTo(2);
        assertThat(bget(b, "/expenses?q=cement").body().path("total").asLong()).isEqualTo(2);
        Resp page = bget(b, "/expenses?limit=2");
        assertThat(page.body().path("items")).hasSize(2);
        assertThat(page.body().path("items").get(0).path("expense_date").asString()).isEqualTo("2026-03-09");
        assertThat(bget(b, "/expenses?from=nope").status()).isEqualTo(422);
    }

    @Test
    void expenseCategoriesAreUniqueRenamableAndArchivable() throws Exception {
        Biz b = biz();
        String id = ok(bpost(b, "/expense-categories", Map.of("name", "Travel")));
        assertCode(bpost(b, "/expense-categories", Map.of("name", "travel")), 409, "CONFLICT");
        assertThat(bpatch(b, "/expense-categories/" + id, Map.of("version", 0, "name", "Travel & stay")).text("name")).isEqualTo("Travel & stay");
        assertCode(bpatch(b, "/expense-categories/" + id, Map.of("version", 0, "name", "x")), 409, "CONFLICT");
        ok(bpost(b, "/expense-categories/" + id + "/archive", Map.of()));
        assertThat(bget(b, "/expense-categories").body().path("items")).isEmpty();
        assertThat(bget(b, "/expense-categories?include_archived=true").body().path("items")).hasSize(1);
        assertThat(expense(b, "1.00", "2026-03-01", null, null, id).status()).isEqualTo(422);
        ok(bpost(b, "/expense-categories/" + id + "/restore", Map.of()));
        assertThat(expense(b, "1.00", "2026-03-01", null, null, id).status()).isEqualTo(201);
    }

    @Test
    void contributorsSeeAndChangeOnlyTheirOwnExpenses() throws Exception {
        Biz b = biz();
        UUID ann = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Map<String, Object> body = Map.of("expense_date", "2026-03-01", "amount", "10.00", "currency", "INR", "description", "Taxi");
        Resp annExpense = as(b, ann, "CONTRIBUTOR", "POST", "/expenses", body);
        assertThat(annExpense.status()).as(annExpense.raw().body()).isEqualTo(201);
        Resp bobExpense = as(b, bob, "CONTRIBUTOR", "POST", "/expenses", body);
        assertThat(bobExpense.status()).isEqualTo(201);

        assertThat(as(b, ann, "CONTRIBUTOR", "GET", "/expenses", null).body().path("total").asLong()).isEqualTo(1);
        assertThat(as(b, ann, "CONTRIBUTOR", "GET", "/expenses/" + annExpense.id(), null).status()).isEqualTo(200);
        Resp foreign = as(b, ann, "CONTRIBUTOR", "GET", "/expenses/" + bobExpense.id(), null);
        Resp missing = as(b, ann, "CONTRIBUTOR", "GET", "/expenses/" + UUID.randomUUID(), null);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.raw().body().replaceAll("\"request_id\":\"[^\"]+\"", "")).isEqualTo(missing.raw().body().replaceAll("\"request_id\":\"[^\"]+\"", ""));
        assertThat(as(b, ann, "CONTRIBUTOR", "PATCH", "/expenses/" + bobExpense.id(), Map.of("version", 0, "amount", "1.00")).status()).isEqualTo(404);
        assertThat(as(b, ann, "CONTRIBUTOR", "PATCH", "/expenses/" + annExpense.id(), Map.of("version", 0, "amount", "12.00")).status()).isEqualTo(200);
        assertThat(as(b, ann, "CONTRIBUTOR", "POST", "/expenses/" + annExpense.id() + "/void", Map.of("reason", "x")).status()).as("no archive right").isEqualTo(403);
        assertThat(bget(b, "/expenses").body().path("total").asLong()).as("owner sees both").isEqualTo(2);
        assertThat(as(b, ann, "CONTRIBUTOR", "GET", "/clients", null).status()).as("clients are limited to other roles").isEqualTo(403);
        assertThat(as(b, ann, "CONTRIBUTOR", "GET", "/invoices", null).status()).isEqualTo(403);
    }
}
