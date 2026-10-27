package com.finora.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;

/** Who may read, export, forecast and which workspace sees what, for reports, exports and forecasts (imports, documents and reconciliation have their own role tests). */
class InsightsAuthorizationTest extends BusinessTestSupport {

    private int status(String method, String path, String token) throws Exception {
        return raw(method, path, token, null).status();
    }

    @Test
    void reportsForecastsAndExportsFollowThePermissionMatrixForEveryRole() throws Exception {
        Biz b = biz();
        UUID user = UUID.randomUUID();
        ok(expense(b, "10.00", "2026-03-01", null, null, null));
        record Case(String role, boolean read, boolean export) {
        }
        List<Case> cases = List.of(new Case("OWNER", true, true), new Case("ACCOUNTANT", true, true), new Case("AUDITOR", true, true), new Case("ADMIN", true, false),
                new Case("MANAGER", false, false), new Case("CONTRIBUTOR", false, false), new Case("VIEWER", false, false));
        for (Case c : cases) {
            String token = btoken(b, user, c.role());
            String space = "/v1/spaces/" + b.space();
            for (String path : List.of("/finance-reports", "/finance-reports/business-performance", "/finance-reports/receivables", "/finance-reports/receivables-by-client",
                    "/finance-reports/project-profitability", "/finance-forecast")) {
                assertThat(status("GET", space + path, token)).as(c.role() + " " + path).isEqualTo(c.read() ? 200 : 403);
            }
            assertThat(bytes("GET", space + "/finance-reports/business-performance/export", token, null, null).status()).as(c.role() + " export").isEqualTo(c.export() ? 200 : 403);
        }
        assertThat(status("GET", "/v1/spaces/" + b.space() + "/finance-reports/business-performance", btoken(b, user, "NOT_A_ROLE"))).isEqualTo(403);
    }

    @Test
    void aPersonalProfileBelongsToItsOwnerOnly() throws Exception {
        Person p = person();
        bank(p, "Main", "0.00");
        for (String role : List.of("ADMIN", "ACCOUNTANT", "AUDITOR", "VIEWER", "MANAGER", "CONTRIBUTOR")) {
            String token = token(p, p.space(), role, List.of("workspace:personal"));
            assertThat(status("GET", base(p) + "/finance-reports/cash-flow", token)).as(role).isEqualTo(403);
            assertThat(status("GET", base(p) + "/finance-forecast", token)).isEqualTo(403);
            assertThat(bytes("GET", base(p) + "/finance-reports/cash-flow/export", token, null, null).status()).isEqualTo(403);
            assertThat(status("GET", base(p) + "/finance-imports", token)).isEqualTo(403);
            assertThat(status("GET", base(p) + "/finance-documents", token)).isEqualTo(403);
            assertThat(status("GET", base(p) + "/finance-reconciliation/lines", token)).isEqualTo(403);
        }
        assertThat(status("GET", base(p) + "/finance-reports/cash-flow", token(p))).isEqualTo(200);
    }

    @Test
    void unknownScopesMissingTokensAndForeignWorkspacesNeverReachAnyNewEndpoint() throws Exception {
        Biz a = biz();
        Biz other = biz();
        String c = client(a, "Secret Client Ltd");
        String inv = issued(a, c, "123.00");
        List<String> paths = List.of("/finance-reports/business-performance", "/finance-reports/receivables-by-client", "/finance-forecast", "/finance-imports", "/finance-documents",
                "/finance-reconciliation/lines", "/finance-reconciliation/summary");
        for (String path : paths) {
            String unknown = token(person(), a.space(), "OWNER", List.of("workspace:unknown"));
            assertThat(status("GET", "/v1/spaces/" + a.space() + path, unknown)).as("unknown scope " + path).isEqualTo(409);
            assertThat(status("GET", "/v1/spaces/" + a.space() + path, token(person(), a.space(), "OWNER", List.of()))).isEqualTo(409);
            assertThat(status("GET", "/v1/spaces/" + a.space() + path, btoken(other, other.owner(), "OWNER"))).as("another workspace's token " + path).isEqualTo(404);
            assertThat(java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/v1/spaces/" + a.space() + path)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).as("no token " + path).isIn(401, 403);
        }
        // the other workspace's own reports and exports contain none of A's data
        assertThat(bget(other, "/finance-reports/receivables-by-client").body().path("currencies")).isEmpty();
        BinResp csv = bytes("GET", "/v1/spaces/" + other.space() + "/finance-reports/receivables-by-client/export", btoken(other, other.owner(), "OWNER"), null, null);
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.text()).doesNotContain("Secret Client").doesNotContain("123.00");
        assertThat(bget(a, "/finance-reports/receivables-by-client").body().toString()).contains("Secret Client Ltd");
        assertThat(inv).isNotNull();
        // a business token cannot read a personal profile's reports by guessing its id
        Person p = person();
        assertThat(status("GET", base(p) + "/finance-reports/cash-flow", btoken(a, a.owner(), "OWNER"))).isEqualTo(404);
    }

    @Test
    void nothingSensitiveIsLoggedByReportsImportsOrDocuments(CapturedOutput output) throws Exception {
        Biz b = biz();
        String c = client(b, "Logged Client Name");
        String i = issued(b, c, "4567.89");
        ok(pay(b, c, "1111.11", "LOGGED-REF", List.of(alloc(i, "1111.11"))));
        bget(b, "/finance-reports/receivables-by-client");
        bytes("GET", "/v1/spaces/" + b.space() + "/finance-reports/receivables-by-client/export", btoken(b, b.owner(), "OWNER"), null, null);
        Resp u = upload("/v1/spaces/" + b.space() + "/finance-imports?kind=EXPENSES&filename=logged-file-name.csv", btoken(b, b.owner(), "OWNER"),
                "Date,Amount,Description\n2026-03-01,2222.22,Logged description\n".getBytes(StandardCharsets.UTF_8), "text/csv");
        assertThat(u.status()).isEqualTo(201);
        Resp d = upload("/v1/spaces/" + b.space() + "/finance-documents?filename=logged-doc-name.pdf", btoken(b, b.owner(), "OWNER"), DocumentsTest.pdf("LOGGED-DOC-CONTENT"), "application/octet-stream");
        assertThat(d.status()).isEqualTo(201);
        assertThat(output.getAll()).doesNotContain("Logged Client Name").doesNotContain("4567.89").doesNotContain("LOGGED-REF").doesNotContain("logged-file-name").doesNotContain("2222.22")
                .doesNotContain("Logged description").doesNotContain("logged-doc-name").doesNotContain("LOGGED-DOC-CONTENT");
    }
}
