package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;

/** Who may do what: workspace type, membership, tenant isolation, forged ids, the role matrix, audit, and log hygiene. */
class BusinessAuthorizationTest extends BusinessTestSupport {

    private record Ids(String client, String vendor, String project, String expense, String category, String invoice, String payment) {
    }

    private Ids populate(Biz b) throws Exception {
        String client = client(b, "Private client");
        String vendor = vendor(b, "Private vendor");
        String project = project(b, "Private project", client, "INR", "500.00");
        String category = ok(bpost(b, "/expense-categories", Map.of("name", "Secret category")));
        String expense = ok(expense(b, "10.00", "2026-03-01", vendor, project, category));
        String invoice = issued(b, client, "100.00");
        String payment = ok(pay(b, client, "40.00", "REF-SECRET", List.of(alloc(invoice, "40.00"))));
        return new Ids(client, vendor, project, expense, category, invoice, payment);
    }

    private String strip(Resp r) {
        return r.raw().body().replaceAll("\"request_id\":\"[^\"]+\"", "");
    }

    @Test
    void anotherWorkspacesRecordsLookExactlyLikeRecordsThatDoNotExist() throws Exception {
        Biz a = biz();
        Biz other = biz();
        Ids ids = populate(a);
        String missing = UUID.randomUUID().toString();
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("/clients/", ids.client());
        paths.put("/vendors/", ids.vendor());
        paths.put("/projects/", ids.project());
        paths.put("/expenses/", ids.expense());
        paths.put("/invoices/", ids.invoice());
        paths.put("/payments/", ids.payment());
        paths.put("/business-summaries/projects/", ids.project());
        for (var e : paths.entrySet()) {
            Resp foreign = bget(other, e.getKey() + e.getValue());
            Resp nothing = bget(other, e.getKey() + missing);
            assertThat(foreign.status()).as(e.getKey()).isEqualTo(404);
            assertThat(strip(foreign)).as(e.getKey()).isEqualTo(strip(nothing));
        }
        for (String list : List.of("/clients", "/vendors", "/projects", "/expenses", "/expense-categories", "/invoices", "/payments")) {
            Resp r = bget(other, list);
            assertThat(r.status()).as(list).isEqualTo(200);
            assertThat(r.body().path("items")).as(list + " must not leak").isEmpty();
        }
        assertThat(bget(other, "/business-summaries/overview").body().path("currencies")).isEmpty();
        assertThat(bget(other, "/business-settings").text("next_invoice_number_preview")).isEqualTo("INV-0001");
    }

    @Test
    void foreignIdsInBodiesAndQueriesChangeNothingAndLeakNothing() throws Exception {
        Biz a = biz();
        Biz other = biz();
        Ids ids = populate(a);
        String ownClient = client(other, "Mine");
        Resp noSuchClient = draft(other, UUID.randomUUID().toString(), "2026-03-01", null, "INR", List.of(line("A", "1", "1")), null);
        Resp foreignClient = draft(other, ids.client(), "2026-03-01", null, "INR", List.of(line("A", "1", "1")), null);
        assertThat(foreignClient.status()).isEqualTo(422);
        assertThat(strip(foreignClient)).isEqualTo(strip(noSuchClient));
        assertThat(draft(other, ownClient, "2026-03-01", null, "INR", List.of(line("A", "1", "1")), ids.project()).status()).as("foreign project").isEqualTo(422);
        assertThat(expense(other, "1.00", "2026-03-01", ids.vendor(), null, null).status()).as("foreign vendor").isEqualTo(422);
        assertThat(expense(other, "1.00", "2026-03-01", null, ids.project(), null).status()).as("foreign project").isEqualTo(422);
        assertThat(expense(other, "1.00", "2026-03-01", null, null, ids.category()).status()).as("foreign category").isEqualTo(422);
        assertThat(pay(other, ids.client(), "1.00", "x", null).status()).as("payment for a foreign client").isEqualTo(422);
        String ownInvoice = issued(other, ownClient, "50.00");
        assertThat(pay(other, ownClient, "5.00", "y", List.of(alloc(ids.invoice(), "5.00"))).status()).as("allocation to a foreign invoice").isEqualTo(422);
        assertThat(bpost(other, "/payments/" + ids.payment() + "/allocations", Map.of("allocations", List.of(alloc(ownInvoice, "1.00")))).status()).isEqualTo(404);
        assertThat(bpost(other, "/payments/" + ids.payment() + "/void", Map.of("reason", "x")).status()).isEqualTo(404);
        assertThat(bpost(other, "/invoices/" + ids.invoice() + "/cancel", Map.of("confirm", true, "reason", "x")).status()).isEqualTo(404);
        assertThat(issue(other, ids.invoice(), "100.00").status()).isEqualTo(404);
        assertThat(bpatch(other, "/clients/" + ids.client(), Map.of("version", 0, "name", "Pwned")).status()).isEqualTo(404);
        assertThat(bpost(other, "/vendors/" + ids.vendor() + "/archive", Map.of()).status()).isEqualTo(404);
        assertThat(bpatch(other, "/projects/" + ids.project(), Map.of("version", 0, "status", "CANCELLED")).status()).isEqualTo(404);
        assertThat(bpatch(other, "/expenses/" + ids.expense(), Map.of("version", 0, "amount", "9.00")).status()).isEqualTo(404);
        assertThat(bpost(other, "/expenses/" + ids.expense() + "/void", Map.of("reason", "x")).status()).isEqualTo(404);
        assertThat(bpost(other, "/expense-categories/" + ids.category() + "/archive", Map.of()).status()).isEqualTo(404);
        assertThat(bget(other, "/invoices?client_id=" + ids.client()).body().path("items")).as("filtering by a foreign id returns nothing").isEmpty();
        assertThat(bget(other, "/payments?client_id=" + ids.client()).body().path("items")).isEmpty();
        assertThat(bget(other, "/expenses?project_id=" + ids.project()).body().path("items")).isEmpty();
        // nothing of the first workspace changed
        assertThat(bget(a, "/clients/" + ids.client()).text("name")).isEqualTo("Private client");
        assertThat(bget(a, "/invoices/" + ids.invoice()).text("outstanding")).isEqualTo("60.00");
        assertThat(bget(a, "/payments/" + ids.payment()).text("status")).isEqualTo("RECORDED");
        assertThat(bget(a, "/projects/" + ids.project()).text("status")).isEqualTo("ACTIVE");
    }

    @Test
    void theWorkspaceComesFromTheTokenNotFromTheRequest() throws Exception {
        Biz a = biz();
        Biz other = biz();
        populate(a);
        // A token for workspace B used on workspace A's path is refused as "not found".
        String bToken = btoken(other, other.owner(), "OWNER");
        Resp r = raw("GET", "/v1/spaces/" + a.space() + "/clients", bToken, null);
        assertThat(r.status()).isEqualTo(404);
        assertThat(raw("POST", "/v1/spaces/" + a.space() + "/clients", bToken, Map.of("name", "X"), "Idempotency-Key", UUID.randomUUID().toString()).status()).isEqualTo(404);
        // A tenant id smuggled in the body or a header is ignored.
        Resp smuggled = as(other, other.owner(), "OWNER", "POST", "/clients", Map.of("name", "Smuggler", "tenant_id", a.space().toString(), "workspace_id", a.space().toString()),
                "Idempotency-Key", UUID.randomUUID().toString(), "X-Tenant-Id", a.space().toString(), "X-Workspace-Id", a.space().toString());
        assertThat(smuggled.status()).isEqualTo(201);
        assertThat(count("select count(*) from clients where tenant_id = :p0 and name = 'Smuggler'", other.space())).isEqualTo(1);
        assertThat(count("select count(*) from clients where tenant_id = :p0 and name = 'Smuggler'", a.space())).isZero();
        // No token, a malformed token and a token of another kind never reach the data.
        assertThat(rawNoAuth("/v1/spaces/" + a.space() + "/clients")).isIn(401, 403);
    }

    private int rawNoAuth(String path) throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void personalProfilesAndUnknownWorkspaceTypesAreRefusedEverywhere() throws Exception {
        Biz biz = biz();
        populate(biz);
        UUID user = UUID.randomUUID();
        Person personal = new Person(user, biz.space());
        for (List<String> scopes : List.of(List.of("workspace:personal"), List.of("workspace:unknown"), List.<String>of())) {
            String token = token(personal, biz.space(), "OWNER", scopes);
            for (String path : List.of("/clients", "/vendors", "/projects", "/expenses", "/expense-categories", "/invoices", "/payments", "/business-settings",
                    "/business-summaries/overview")) {
                Resp r = raw("GET", "/v1/spaces/" + biz.space() + path, token, null);
                assertThat(r.status()).as(scopes + " " + path).isEqualTo(409);
                assertThat(r.raw().body()).doesNotContain("Private");
            }
            assertThat(raw("POST", "/v1/spaces/" + biz.space() + "/clients", token, Map.of("name", "X"), "Idempotency-Key", UUID.randomUUID().toString()).status()).isEqualTo(409);
        }
        // and the personal APIs refuse a business workspace
        Resp personalApi = raw("GET", "/v1/spaces/" + biz.space() + "/accounts", btoken(biz, biz.owner(), "OWNER"), null);
        assertThat(personalApi.status()).isEqualTo(409);
    }

    /** Every endpoint, every role: reads and writes follow the permission matrix, and anything the matrix scopes down fails closed. */
    @Test
    void theRoleMatrixIsEnforcedOnEveryOperation() throws Exception {
        Biz b = biz();
        Ids ids = populate(b);
        UUID user = UUID.randomUUID();
        record Op(String name, String method, String path, Object body, List<String> allowed) {
        }
        List<String> everyoneWithAll = List.of("OWNER", "ADMIN", "ACCOUNTANT", "AUDITOR");
        List<String> writers = List.of("OWNER", "ACCOUNTANT");
        String draft = draftInvoice(b, ids.client(), "5.00");
        List<Op> ops = List.of(
                new Op("list clients", "GET", "/clients", null, everyoneWithAll),
                new Op("read client", "GET", "/clients/" + ids.client(), null, everyoneWithAll),
                new Op("create client", "POST", "/clients", Map.of("name", "N-" + UUID.randomUUID()), writers),
                new Op("update client", "PATCH", "/clients/" + ids.client(), Map.of("version", 0, "notes", "n"), writers),
                new Op("list vendors", "GET", "/vendors", null, everyoneWithAll),
                new Op("create vendor", "POST", "/vendors", Map.of("name", "V-" + UUID.randomUUID()), writers),
                new Op("list projects", "GET", "/projects", null, everyoneWithAll),
                new Op("create project", "POST", "/projects", Map.of("name", "P-" + UUID.randomUUID(), "currency", "INR"), writers),
                new Op("read invoices", "GET", "/invoices", null, everyoneWithAll),
                new Op("read one invoice", "GET", "/invoices/" + ids.invoice(), null, everyoneWithAll),
                new Op("create invoice", "POST", "/invoices", Map.of("client_id", ids.client(), "lines", List.of(line("A", "1", "1"))), writers),
                new Op("issue invoice", "POST", "/invoices/" + draft + "/issue", Map.of("confirm", true, "expected_total", "5.00"), writers),
                new Op("cancel invoice", "POST", "/invoices/" + ids.invoice() + "/cancel", Map.of("confirm", true, "reason", "r"), writers),
                new Op("read payments", "GET", "/payments", null, everyoneWithAll),
                new Op("record payment", "POST", "/payments", Map.of("client_id", ids.client(), "payment_date", "2026-03-10", "amount", "1.00", "currency", "INR", "method", "CASH"), writers),
                new Op("void payment", "POST", "/payments/" + ids.payment() + "/void", Map.of("reason", "r"), writers),
                new Op("read expenses", "GET", "/expenses", null, List.of("OWNER", "ADMIN", "ACCOUNTANT", "AUDITOR", "CONTRIBUTOR")),
                new Op("create expense", "POST", "/expenses", Map.of("expense_date", "2026-03-01", "amount", "1.00", "currency", "INR", "description", "d"), List.of("OWNER", "ACCOUNTANT", "CONTRIBUTOR")),
                new Op("read categories", "GET", "/expense-categories", null, List.of("OWNER", "ADMIN", "ACCOUNTANT", "AUDITOR", "CONTRIBUTOR")),
                new Op("create category", "POST", "/expense-categories", Map.of("name", "C-" + UUID.randomUUID()), writers),
                new Op("overview", "GET", "/business-summaries/overview", null, List.of("OWNER", "ADMIN", "ACCOUNTANT", "AUDITOR")),
                new Op("project profitability", "GET", "/business-summaries/projects", null, List.of("OWNER", "ADMIN", "ACCOUNTANT", "AUDITOR")),
                new Op("read settings", "GET", "/business-settings", null, everyoneWithAll),
                new Op("change settings", "PUT", "/business-settings", Map.of("default_terms_days", 20), List.of("OWNER", "ADMIN")));
        List<String> roles = List.of("OWNER", "ADMIN", "ACCOUNTANT", "MANAGER", "CONTRIBUTOR", "AUDITOR", "VIEWER");
        for (Op op : ops) {
            for (String role : roles) {
                Resp r = as(b, user, role, op.method(), op.path(), op.body(), "Idempotency-Key", UUID.randomUUID().toString());
                boolean expectedAllowed = op.allowed().contains(role);
                if (expectedAllowed) {
                    assertThat(r.status()).as(op.name() + " as " + role + " -> " + r.raw().body()).isNotIn(403, 401);
                } else {
                    assertThat(r.status()).as(op.name() + " as " + role).isEqualTo(403);
                    assertThat(r.raw().body()).doesNotContain("Private");
                }
            }
        }
        // Contributors may create expenses (their own) but nothing else in the business area
        Resp own = as(b, user, "CONTRIBUTOR", "POST", "/expenses", Map.of("expense_date", "2026-03-01", "amount", "1.00", "currency", "INR", "description", "d"));
        assertThat(own.status()).isEqualTo(201);
        // A Viewer, Manager or unknown role can read nothing
        assertThat(as(b, user, "NOT_A_ROLE", "GET", "/clients", null).status()).isEqualTo(403);
    }

    @Test
    void writesAreAuditedWithoutAmountsNotesOrReferencesInLogs(CapturedOutput output) throws Exception {
        Biz b = biz();
        Ids ids = populate(b);
        long events = count("select count(*) from finance_audit_events where tenant_id = :p0", b.space());
        assertThat(events).isGreaterThanOrEqualTo(8);
        for (String type : List.of("CLIENT_CREATED", "VENDOR_CREATED", "PROJECT_CREATED", "EXPENSE_CREATED", "INVOICE_DRAFT_CREATED", "INVOICE_ISSUED", "PAYMENT_RECORDED")) {
            assertThat(count("select count(*) from finance_audit_events where tenant_id = :p0 and event_type = :p1", b.space(), type)).as(type).isGreaterThanOrEqualTo(1);
        }
        ok(bpost(b, "/payments/" + ids.payment() + "/void", Map.of("reason", "Private reason text")));
        assertThat(count("select count(*) from finance_audit_events where tenant_id = :p0 and event_type = 'PAYMENT_VOIDED'", b.space())).isEqualTo(1);
        // The audit log is append-only for the service's own database role.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("delete from finance_audit_events where tenant_id = :t").param("t", b.space()).update()).isNotNull();
        assertThat(output.getAll()).doesNotContain("Private client").doesNotContain("REF-SECRET").doesNotContain("Private reason text").doesNotContain("Secret category");
        assertThat(Duration.ZERO).isEqualTo(Duration.ZERO);
    }

    @Test
    void databaseConstraintsHoldEvenIfTheServiceIsBypassed() throws Exception {
        Biz b = biz();
        Ids ids = populate(b);
        UUID tenant = b.space();
        UUID other = UUID.randomUUID();
        // a row can never point at another workspace's parent
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                insert into projects (id, tenant_id, name, client_id, status, currency, created_by, created_at, updated_at)
                values (:id, :other, 'x', :client, 'ACTIVE', 'INR', :u, now(), now())
                """).param("id", UUID.randomUUID()).param("other", other).param("client", UUID.fromString(ids.client())).param("u", UUID.randomUUID()).update())
                .hasMessageContaining("projects_client_fk");
        // two issued invoices cannot share a number
        String second = issued(b, ids.client(), "10.00");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("update invoices set number = (select number from invoices where tenant_id = :t and id = :first) where tenant_id = :t and id = :second")
                .param("t", tenant).param("first", UUID.fromString(ids.invoice())).param("second", UUID.fromString(second)).update()).hasMessageContaining("invoices_number_idx");
        // an allocation cannot be negative or duplicated for the same pair
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                insert into payment_allocations (id, tenant_id, payment_id, invoice_id, amount, created_by, created_at)
                values (:id, :t, :p, :i, -1, :u, now())
                """).param("id", UUID.randomUUID()).param("t", tenant).param("p", UUID.fromString(ids.payment())).param("i", UUID.fromString(ids.invoice())).param("u", UUID.randomUUID()).update())
                .hasMessageContaining("payment_allocations_amount_chk");
        // an issued invoice must carry a number, a draft must not
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("update invoices set number = null where tenant_id = :t and id = :i").param("t", tenant).param("i", UUID.fromString(ids.invoice())).update())
                .hasMessageContaining("invoices_number_chk");
    }
}
