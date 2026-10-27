package com.finora.finance.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.finance.BusinessTestSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Helpers shared by the assistant integration tests. Today is 2026-03-15, so "last month" is February 2026. */
abstract class AssistantTestSupport extends BusinessTestSupport {

    protected Resp ask(Person p, String question) throws Exception {
        return post(p, "/finance-assistant/ask", Map.of("question", question));
    }

    protected Resp bask(Biz b, String question) throws Exception {
        return as(b, b.owner(), "OWNER", "POST", "/finance-assistant/ask", Map.of("question", question));
    }

    protected Resp tx2(Person p, String kind, String account, String amount, String date, String category, String payee, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("account_id", account);
        body.put("amount", amount);
        body.put("occurred_on", date);
        if (category != null) {
            body.put("category_id", category);
        }
        if (payee != null) {
            body.put("payee", payee);
        }
        if (note != null) {
            body.put("note", note);
        }
        return post(p, "/transactions", body);
    }

    /** The first section whose title contains the text (and, if given, in that currency). */
    protected JsonNode section(Resp r, String titlePart, String currency) {
        for (JsonNode s : r.body().path("sections")) {
            if (s.path("title").asString().contains(titlePart) && (currency == null || currency.equals(s.path("currency").asString()))) {
                return s;
            }
        }
        throw new AssertionError("no section '" + titlePart + "' " + currency + " in " + r.raw().body());
    }

    /** The value in the second column of the row whose first cell equals the label. */
    protected String cell(JsonNode section, String label) {
        for (JsonNode row : section.path("rows")) {
            if (label.equals(row.get(0).asString())) {
                return row.get(1).asString();
            }
        }
        throw new AssertionError("no row '" + label + "' in " + section);
    }

    protected long audit(Object tenant, String type) {
        return jdbc.sql("select count(*) from finance_audit_events where tenant_id = :t and event_type = :e").param("t", tenant).param("e", type).query(Long.class).single();
    }

    protected void assertAnswered(Resp r) {
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.text("status")).as(r.raw().body()).isEqualTo("ANSWERED");
    }
}
