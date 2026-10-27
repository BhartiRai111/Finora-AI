package com.finora.finance.step21;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.finance.BusinessTestSupport;
import com.finora.finance.gst.GstCodes;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * GST-aware details for the supported cases (intra-state CGST+SGST, inter-state IGST, INR), the refusals for everything else, and the summary,
 * reconciliation and export built on them. Supplier GSTIN 27AAPFU0939F1ZV (Maharashtra, a published sample number). Today is 2026-03-15.
 */
class GstTest extends BusinessTestSupport {

    private static final String SUPPLIER = "27AAPFU0939F1ZV";
    private static final String KARNATAKA = "29AAGCB7383J1Z4";

    private static String gstin(String stateAndPan, String entity) {
        String first14 = stateAndPan + entity + "Z";
        return first14 + GstCodes.checkCharacter(first14);
    }

    private Biz registered() throws Exception {
        Biz b = biz();
        Resp r = as(b, b.owner(), "OWNER", "PUT", "/finance-gst/registration", Map.of("gst_registered", true, "gstin", SUPPLIER));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return b;
    }

    private String invoice(Biz b, String client, List<Map<String, Object>> lines, String currency, boolean issue) throws Exception {
        String id = ok(draft(b, client, "2026-02-10", "2026-03-10", currency, lines, null));
        if (issue) {
            String total = bget(b, "/invoices/" + id).text("total");
            ok(issue(b, id, total));
        }
        return id;
    }

    private Resp gst(Biz b, String id, String place) throws Exception {
        return as(b, b.owner(), "OWNER", "PUT", "/finance-gst/invoices/" + id, place == null ? Map.of() : Map.of("place_of_supply", place));
    }

    // ------------------------------------------------------------------ registration

    @Test
    void registrationValidatesTheGstinAndNothingIsAssumedForAnUnregisteredWorkspace() throws Exception {
        Biz b = biz();
        String c = client(b, "Acme");
        String inv = invoice(b, c, List.of(line("Work", "1", "1000.00", "0", "18")), "INR", false);
        Resp none = gst(b, inv, "27");
        assertThat(none.status()).isEqualTo(409);
        assertThat(none.text("detail")).contains("not set up as GST-registered").contains("nothing is assumed");
        for (String bad : new String[] {"27AAPFU0939F1ZX", "27AAPFU0939F1Z", "99", "40AAPFU0939F1ZV"}) {
            Resp r = as(b, b.owner(), "OWNER", "PUT", "/finance-gst/registration", Map.of("gst_registered", true, "gstin", bad));
            assertThat(r.status()).as(bad).isEqualTo(422);
        }
        assertThat(as(b, b.owner(), "OWNER", "PUT", "/finance-gst/registration", Map.of("gst_registered", true)).status()).as("registered needs a GSTIN").isEqualTo(422);
        Resp ok = as(b, b.owner(), "OWNER", "PUT", "/finance-gst/registration", Map.of("gst_registered", true, "gstin", SUPPLIER.toLowerCase()));
        assertThat(ok.status()).as(ok.raw().body()).isEqualTo(200);
        assertThat(ok.text("gstin")).isEqualTo(SUPPLIER);
        assertThat(ok.text("state_name")).isEqualTo("Maharashtra");
        Resp reader = as(b, UUID.randomUUID(), "ACCOUNTANT", "GET", "/finance-gst/registration", null);
        assertThat(reader.text("gstin")).as("masked for roles that cannot change it").isEqualTo("27**********1ZV");
        assertThat(as(b, UUID.randomUUID(), "ACCOUNTANT", "PUT", "/finance-gst/registration", Map.of("gst_registered", false)).status()).isEqualTo(403);
        assertThat(as(b, UUID.randomUUID(), "VIEWER", "GET", "/finance-gst/registration", null).status()).isEqualTo(403);
        assertThat(as(b, b.owner(), "OWNER", "PUT", "/finance-gst/registration", Map.of("gst_registered", false)).body().path("gst_registered").asBoolean()).isFalse();
        assertThat(jdbc.sql("select string_agg(coalesce(detail, ''), ' ') from finance_audit_events where tenant_id = :t").param("t", b.space()).query(String.class).single()).doesNotContain(SUPPLIER);
        Person p = person();
        assertThat(get(p, "/finance-gst/registration").status()).as("GST exists only in business workspaces").isEqualTo(409);
        assertThat(get(p, "/finance-gst/summary").status()).isEqualTo(409);
    }

    // ------------------------------------------------------------------ invoice GST

    @Test
    void anIntraStateSupplySplitsTaxIntoEqualCgstAndSgstPerRateAndTheSplitReconcilesToTheInvoice() throws Exception {
        Biz b = registered();
        String c = client(b, "Local Buyer");
        String inv = invoice(b, c, List.of(line("A", "1", "1000.00", "0", "18"), line("B", "1", "200.00", "0", "5"), line("C", "2", "500.00", "100.00", "18")), "INR", false);
        Resp r = gst(b, inv, "27");
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("RECORDED");
        assertThat(r.text("supply_type")).isEqualTo("INTRA_STATE");
        assertThat(r.text("supplier_state")).isEqualTo("27");
        assertThat(r.text("place_of_supply_name")).isEqualTo("Maharashtra");
        assertThat(r.text("rule_version")).isEqualTo(GstCodes.RULE_VERSION);
        JsonNode rows = r.body().path("breakdown");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("tax_percent").asString()).isEqualTo("5.00");
        assertThat(rows.get(0).path("taxable_amount").asString()).isEqualTo("200.00");
        assertThat(rows.get(0).path("cgst").asString()).isEqualTo("5.00");
        assertThat(rows.get(0).path("sgst").asString()).isEqualTo("5.00");
        assertThat(rows.get(0).path("igst").asString()).isEqualTo("0.00");
        assertThat(rows.get(1).path("tax_percent").asString()).isEqualTo("18.00");
        assertThat(rows.get(1).path("taxable_amount").asString()).as("1000.00 + (1000.00 - 100.00)").isEqualTo("1900.00");
        assertThat(rows.get(1).path("total_tax").asString()).isEqualTo("342.00");
        assertThat(rows.get(1).path("cgst").asString()).isEqualTo("171.00");
        assertThat(r.text("invoice_tax_total")).isEqualTo("352.00");
        assertThat(count("select count(*) from invoice_gst_breakdown where tenant_id = :p0 and total_tax = cgst + sgst + igst", b.space())).isEqualTo(2);
        assertThat(bget(b, "/finance-gst/invoices/" + inv).text("status")).isEqualTo("RECORDED");
    }

    @Test
    void anOddPaisaGoesToSgstSoCgstPlusSgstAlwaysEqualsTheTaxExactly() throws Exception {
        Biz b = registered();
        String c = client(b, "Tiny");
        String inv = invoice(b, c, List.of(line("Small", "1", "3.00", "0", "5")), "INR", false);
        JsonNode row = gst(b, inv, "27").body().path("breakdown").get(0);
        assertThat(row.path("total_tax").asString()).isEqualTo("0.15");
        assertThat(row.path("cgst").asString()).isEqualTo("0.07");
        assertThat(row.path("sgst").asString()).isEqualTo("0.08");
    }

    @Test
    void anInterStateSupplyCarriesIgstOnlyAndTheRecipientGstinIsSnapshotted() throws Exception {
        Biz b = registered();
        String c = ok(bpost(b, "/clients", Map.of("name", "Far Buyer", "gstin", KARNATAKA)));
        String inv = invoice(b, c, List.of(line("A", "1", "1000.00", "0", "18")), "INR", false);
        Resp r = gst(b, inv, "29");
        assertThat(r.text("supply_type")).isEqualTo("INTER_STATE");
        JsonNode row = r.body().path("breakdown").get(0);
        assertThat(row.path("igst").asString()).isEqualTo("180.00");
        assertThat(row.path("cgst").asString()).isEqualTo("0.00");
        assertThat(row.path("sgst").asString()).isEqualTo("0.00");
        assertThat(r.text("recipient_gstin")).isEqualTo(KARNATAKA);
        assertThat(r.text("recipient_state")).isEqualTo("29");
        ok(as(b, b.owner(), "OWNER", "PATCH", "/clients/" + c, Map.of("gstin", gstin("27AAACC4636H", "1"), "version", 0)));
        assertThat(bget(b, "/finance-gst/invoices/" + inv).text("recipient_gstin")).as("a later client change does not rewrite the snapshot").isEqualTo(KARNATAKA);
    }

    @Test
    void contextThatCannotBeClassifiedIsRefusedNotGuessed() throws Exception {
        Biz b = registered();
        String c = client(b, "Buyer");
        String inv = invoice(b, c, List.of(line("A", "1", "1000.00", "0", "18")), "INR", false);
        assertThat(gst(b, inv, null).status()).as("no place of supply").isEqualTo(422);
        assertThat(gst(b, inv, null).text("detail")).contains("never assumed");
        for (String bad : new String[] {"00", "97", "99", "7", "ABC", "400"}) {
            assertThat(gst(b, inv, bad).status()).as(bad).isEqualTo(422);
        }
        String usd = invoice(b, c, List.of(line("A", "1", "100.00", "0", "18")), "USD", false);
        Resp r = gst(b, usd, "27");
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("detail")).contains("only supported for INR");
        assertThat(gst(b, UUID.randomUUID().toString(), "27").status()).isEqualTo(404);
        // an intra-state supply inside a union territory without a legislature may need UTGST: refused
        Biz ut = biz();
        String chandigarh = gstin("04AAGCB7383J", "1");
        assertThat(as(ut, ut.owner(), "OWNER", "PUT", "/finance-gst/registration", Map.of("gst_registered", true, "gstin", chandigarh)).status()).isEqualTo(200);
        String utInv = invoice(ut, client(ut, "UT Buyer"), List.of(line("A", "1", "1000.00", "0", "18")), "INR", false);
        Resp refused = gst(ut, utInv, "04");
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.text("detail")).contains("UTGST").contains("not supported");
        assertThat(gst(ut, utInv, "27").status()).as("inter-state from a union territory is IGST").isEqualTo(200);
        assertThat(count("select count(*) from invoice_gst_details where tenant_id = :p0", b.space())).isZero();
    }

    @Test
    void aDraftCanBeRecalculatedButAnIssuedInvoiceSnapshotIsFrozenAndNeverSilentlyRecalculated() throws Exception {
        Biz b = registered();
        String c = client(b, "Buyer");
        String draft = invoice(b, c, List.of(line("A", "1", "1000.00", "0", "18")), "INR", false);
        Resp preview = as(b, b.owner(), "OWNER", "PUT", "/finance-gst/invoices/" + draft + "?preview=true", Map.of("place_of_supply", "29"));
        assertThat(preview.text("status")).isEqualTo("PREVIEW");
        assertThat(preview.body().path("breakdown").get(0).path("igst").asString()).isEqualTo("180.00");
        assertThat(bget(b, "/finance-gst/invoices/" + draft).text("status")).as("a preview saves nothing").isEqualTo("NOT_RECORDED");
        assertThat(gst(b, draft, "27").text("supply_type")).isEqualTo("INTRA_STATE");
        assertThat(gst(b, draft, "29").text("supply_type")).as("a draft can be recalculated").isEqualTo("INTER_STATE");
        assertThat(count("select count(*) from invoice_gst_breakdown where tenant_id = :p0 and invoice_id = :p1", b.space(), UUID.fromString(draft))).as("replaced, not duplicated").isEqualTo(1);
        ok(issue(b, draft, "1180.00"));
        Resp frozen = gst(b, draft, "27");
        assertThat(frozen.status()).isEqualTo(409);
        assertThat(frozen.code()).isEqualTo("INVOICE_IMMUTABLE");
        assertThat(bget(b, "/finance-gst/invoices/" + draft).text("supply_type")).isEqualTo("INTER_STATE");
        // an issued invoice with no snapshot yet may have one recorded once
        String late = invoice(b, c, List.of(line("A", "1", "1000.00", "0", "18")), "INR", true);
        assertThat(gst(b, late, "27").status()).isEqualTo(200);
        assertThat(gst(b, late, "29").status()).isEqualTo(409);
        // a changed GST registration does not touch an existing snapshot
        as(b, b.owner(), "OWNER", "PUT", "/finance-gst/registration", Map.of("gstin", KARNATAKA));
        assertThat(bget(b, "/finance-gst/invoices/" + late).text("supplier_gstin")).isEqualTo(SUPPLIER);
        // a cancelled invoice has none
        String cancelled = invoice(b, c, List.of(line("A", "1", "10.00", "0", "18")), "INR", true);
        ok(bpost(b, "/invoices/" + cancelled + "/cancel", Map.of("confirm", true, "reason", "mistake")));
        assertThat(gst(b, cancelled, "27").status()).isEqualTo(409);
    }

    @Test
    void aSnapshotThatNoLongerMatchesItsDraftIsFlaggedAndRolesAreEnforced() throws Exception {
        Biz b = registered();
        String c = client(b, "Buyer");
        String inv = invoice(b, c, List.of(line("A", "1", "1000.00", "0", "18")), "INR", false);
        ok(gst(b, inv, "27"));
        jdbc.sql("update invoices set tax_total = tax_total + 1, total = total + 1 where tenant_id = :t and id = :id").param("t", b.space()).param("id", UUID.fromString(inv)).update();
        Resp stale = bget(b, "/finance-gst/invoices/" + inv);
        assertThat(stale.body().path("snapshot_stale").asBoolean()).isTrue();
        assertThat(stale.body().path("warnings").toString()).contains("changed after these GST details were recorded");
        UUID user = UUID.randomUUID();
        assertThat(as(b, user, "ACCOUNTANT", "GET", "/finance-gst/invoices/" + inv, null).status()).isEqualTo(200);
        for (String role : List.of("VIEWER", "AUDITOR", "NOT_A_ROLE")) {
            assertThat(as(b, user, role, "PUT", "/finance-gst/invoices/" + inv, Map.of("place_of_supply", "27")).status()).as(role).isEqualTo(403);
        }
        Biz other = registered();
        assertThat(as(other, other.owner(), "OWNER", "GET", "/finance-gst/invoices/" + inv, null).status()).as("another workspace cannot see it").isEqualTo(404);
        assertThat(as(other, other.owner(), "OWNER", "PUT", "/finance-gst/invoices/" + inv, Map.of("place_of_supply", "27")).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ expense GST

    private Resp expenseGst(Biz b, String id, Map<String, Object> body) throws Exception {
        return as(b, b.owner(), "OWNER", "PUT", "/finance-gst/expenses/" + id, body);
    }

    private String billFrom(Biz b, String vendorBody, String amount, String date) throws Exception {
        Map<String, Object> v = new java.util.LinkedHashMap<>();
        v.put("name", "Vendor " + UUID.randomUUID().toString().substring(0, 6));
        if (vendorBody != null) {
            v.put("gstin", vendorBody);
        }
        String vendor = ok(bpost(b, "/vendors", v));
        return ok(expense(b, amount, date, vendor, null, null));
    }

    @Test
    void expenseGstIsEnteredFromTheBillAndMustBalanceAndMatchTheSupplyType() throws Exception {
        Biz b = registered();
        String same = billFrom(b, gstin("27AAACC4636H", "1"), "1180.00", "2026-02-10");
        Resp ok = expenseGst(b, same, Map.of("place_of_supply", "27", "taxable_amount", "1000.00", "cgst", "90.00", "sgst", "90.00"));
        assertThat(ok.status()).as(ok.raw().body()).isEqualTo(200);
        assertThat(ok.text("supply_type")).isEqualTo("INTRA_STATE");
        assertThat(ok.text("total_tax")).isEqualTo("180.00");
        assertThat(bget(b, "/finance-gst/expenses/" + same).text("status")).isEqualTo("RECORDED");
        for (Map<String, Object> bad : List.of(
                Map.<String, Object>of("place_of_supply", "27", "taxable_amount", "1000.00", "cgst", "90.00", "sgst", "89.00"),
                Map.<String, Object>of("place_of_supply", "27", "taxable_amount", "1000.00", "cgst", "180.00"),
                Map.<String, Object>of("place_of_supply", "27", "taxable_amount", "1000.00", "igst", "180.00"),
                Map.<String, Object>of("place_of_supply", "27", "taxable_amount", "1000.00", "cgst", "100.00", "sgst", "80.00"),
                Map.<String, Object>of("place_of_supply", "29", "taxable_amount", "1000.00", "cgst", "90.00", "sgst", "90.00"),
                Map.<String, Object>of("place_of_supply", "27", "taxable_amount", "1000.00", "cgst", "-90.00", "sgst", "270.00"),
                Map.<String, Object>of("place_of_supply", "27", "taxable_amount", "1000.005", "cgst", "89.995", "sgst", "90.00"),
                Map.<String, Object>of("taxable_amount", "1000.00", "cgst", "90.00", "sgst", "90.00"),
                Map.<String, Object>of("place_of_supply", "97", "taxable_amount", "1180.00"))) {
            assertThat(expenseGst(b, same, bad).status()).as(bad.toString()).isEqualTo(422);
        }
        String inter = billFrom(b, KARNATAKA, "1180.00", "2026-02-11");
        Resp igst = expenseGst(b, inter, Map.of("place_of_supply", "27", "taxable_amount", "1000.00", "igst", "180.00"));
        assertThat(igst.text("supply_type")).isEqualTo("INTER_STATE");
        String noGstin = billFrom(b, null, "500.00", "2026-02-12");
        assertThat(expenseGst(b, noGstin, Map.of("place_of_supply", "27", "taxable_amount", "400.00", "igst", "100.00")).status()).as("tax without a vendor GSTIN").isEqualTo(422);
        Resp zero = expenseGst(b, noGstin, Map.of("place_of_supply", "27", "taxable_amount", "500.00"));
        assertThat(zero.status()).isEqualTo(200);
        assertThat(zero.body().path("warnings").toString()).contains("no valid GSTIN");
        String usd = ok(as(b, b.owner(), "OWNER", "POST", "/expenses", Map.of("expense_date", "2026-02-10", "amount", "10.00", "currency", "USD", "description", "Tool"), "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(expenseGst(b, usd, Map.of("place_of_supply", "27", "taxable_amount", "10.00")).status()).isEqualTo(422);
        assertThat(as(b, UUID.randomUUID(), "VIEWER", "PUT", "/finance-gst/expenses/" + same, Map.of("place_of_supply", "27", "taxable_amount", "1180.00")).status()).isEqualTo(403);
        assertThat(as(b, UUID.randomUUID(), "CONTRIBUTOR", "PUT", "/finance-gst/expenses/" + same, Map.of("place_of_supply", "27", "taxable_amount", "1180.00")).status()).isEqualTo(403);
        Biz unregistered = biz();
        String e2 = ok(expense(unregistered, "100.00", "2026-02-10", null, null, null));
        assertThat(as(unregistered, unregistered.owner(), "OWNER", "PUT", "/finance-gst/expenses/" + e2, Map.of("place_of_supply", "27", "taxable_amount", "100.00")).status()).isEqualTo(409);
    }

    // ------------------------------------------------------------------ summary, reconciliation, export

    @Test
    void theSummaryIncludesOnlySupportedRecordsFlagsTheRestAndReconcilesToTheInvoices() throws Exception {
        Biz b = registered();
        String near = client(b, "Near Buyer");
        String far = ok(bpost(b, "/clients", Map.of("name", "Far Buyer", "gstin", KARNATAKA)));
        String i1 = invoice(b, near, List.of(line("A", "1", "1000.00", "0", "18")), "INR", true);
        String i2 = invoice(b, far, List.of(line("A", "1", "2000.00", "0", "18"), line("B", "1", "100.00", "0", "5")), "INR", true);
        String i3 = invoice(b, near, List.of(line("A", "1", "3.00", "0", "5")), "INR", true);
        String noDetails = invoice(b, near, List.of(line("A", "1", "400.00", "0", "18")), "INR", true);
        String usd = invoice(b, near, List.of(line("A", "1", "50.00", "0", "18")), "USD", true);
        String staleInv = invoice(b, near, List.of(line("A", "1", "600.00", "0", "18")), "INR", true);
        String draftOnly = invoice(b, near, List.of(line("A", "1", "700.00", "0", "18")), "INR", false);
        ok(gst(b, i1, "27"));
        ok(gst(b, i2, "29"));
        ok(gst(b, i3, "27"));
        ok(gst(b, staleInv, "27"));
        jdbc.sql("update invoice_gst_details set invoice_total = invoice_total + 1 where tenant_id = :t and invoice_id = :id").param("t", b.space()).param("id", UUID.fromString(staleInv)).update();
        String vendorGstin = gstin("27AAACC4636H", "1");
        String bill = billFrom(b, vendorGstin, "1180.00", "2026-02-20");
        ok(expenseGst(b, bill, Map.of("place_of_supply", "27", "taxable_amount", "1000.00", "cgst", "90.00", "sgst", "90.00")));
        billFrom(b, vendorGstin, "236.00", "2026-02-21");
        Resp r = bget(b, "/finance-gst/summary?from=2026-02-01&to=2026-02-28");
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        assertThat(r.text("format")).isEqualTo("FINORA_GST_PREPARATION_WORKSHEET_V1");
        assertThat(r.body().path("official_filing_format").asBoolean()).isFalse();
        assertThat(r.text("notice")).contains("NOT an official GST return");
        JsonNode sales = r.body().path("sales");
        assertThat(sales.path("invoices_included").asInt()).isEqualTo(3);
        assertThat(sales.path("taxable_amount").asString()).isEqualTo("3103.00");
        assertThat(sales.path("cgst").asString()).isEqualTo("90.07");
        assertThat(sales.path("sgst").asString()).isEqualTo("90.08");
        assertThat(sales.path("igst").asString()).isEqualTo("365.00");
        assertThat(sales.path("total_tax").asString()).isEqualTo("545.15");
        assertThat(sales.path("excluded_invoices").path("EXCLUDED_NO_GST_DETAILS").asInt()).isEqualTo(1);
        assertThat(sales.path("excluded_invoices").path("EXCLUDED_STALE_SNAPSHOT").asInt()).isEqualTo(1);
        assertThat(sales.path("excluded_non_inr_invoices").path("USD").asInt()).isEqualTo(1);
        assertThat(sales.path("by_month")).hasSize(1);
        JsonNode purchases = r.body().path("purchases");
        assertThat(purchases.path("expenses_included").asInt()).isEqualTo(1);
        assertThat(purchases.path("taxable_amount").asString()).isEqualTo("1000.00");
        assertThat(purchases.path("total_tax").asString()).isEqualTo("180.00");
        assertThat(purchases.path("excluded_expenses").path("EXCLUDED_NO_GST_DETAILS").asInt()).isEqualTo(1);
        JsonNode rec = r.body().path("reconciliation");
        assertThat(rec.path("sales_reconciled").asBoolean()).isTrue();
        assertThat(rec.path("purchases_reconciled").asBoolean()).isTrue();
        assertThat(rec.path("included_invoice_tax_total").asString()).isEqualTo("545.15");
        assertThat(rec.path("excluded_invoice_tax_total").asString()).as("400 @18% + 600 @18%").isEqualTo("180.00");
        assertThat(rec.path("issued_inr_invoice_tax_total").asString()).isEqualTo("725.15");
        assertThat(r.body().path("flags").toString()).contains("EXCLUDED_NO_GST_DETAILS").contains("EXCLUDED_STALE_SNAPSHOT").contains("USD invoice(s) excluded");
        assertThat(r.body().path("limitations").toString()).contains("UTGST").contains("no rate table");
        assertThat(r.body().path("registration").path("gstin_masked").asString()).isEqualTo("27**********1ZV");
        assertThat(r.raw().body()).as("the full GSTIN is not in the summary").doesNotContain(SUPPLIER);
        assertThat(noDetails).isNotNull();
        assertThat(usd).isNotNull();
        assertThat(draftOnly).isNotNull();
        assertThat(bget(b, "/finance-gst/summary?from=2026-03-01&to=2026-03-31").body().path("sales").path("invoices_included").asInt()).isZero();
        assertThat(bget(b, "/finance-gst/summary").body().path("period").path("from").asString()).as("default: the last complete month").isEqualTo("2026-02-01");
        assertThat(bget(b, "/finance-gst/summary?from=2026-02-01").status()).isEqualTo(422);
        assertThat(bget(b, "/finance-gst/summary?from=2026-03-01&to=2026-02-01").status()).isEqualTo(422);
    }

    @Test
    void transactionsAndTheCsvExportAreDerivedFromTheSameRecordsAndProtectedAgainstFormulaInjection() throws Exception {
        Biz b = registered();
        String evil = client(b, "=HYPERLINK(\"http://evil.example\",\"x\")");
        String i1 = invoice(b, evil, List.of(line("A", "1", "1000.00", "0", "18"), line("B", "1", "100.00", "0", "5")), "INR", true);
        ok(gst(b, i1, "27"));
        invoice(b, evil, List.of(line("A", "1", "400.00", "0", "18")), "INR", true);
        Resp tx = bget(b, "/finance-gst/transactions?from=2026-02-01&to=2026-02-28");
        assertThat(tx.status()).isEqualTo(200);
        JsonNode rows = tx.body().path("rows");
        assertThat(rows).hasSize(3);
        double cgst = 0;
        double sgst = 0;
        int included = 0;
        for (JsonNode row : rows) {
            if ("INCLUDED".equals(row.path("status").asString())) {
                included++;
                cgst += Double.parseDouble(row.path("cgst").asString());
                sgst += Double.parseDouble(row.path("sgst").asString());
            } else {
                assertThat(row.path("status").asString()).isEqualTo("EXCLUDED_NO_GST_DETAILS");
                assertThat(row.path("cgst").asString()).isEmpty();
            }
        }
        assertThat(included).isEqualTo(2);
        Resp summary = bget(b, "/finance-gst/summary?from=2026-02-01&to=2026-02-28");
        assertThat(Double.parseDouble(summary.body().path("sales").path("cgst").asString())).isEqualTo(cgst);
        assertThat(Double.parseDouble(summary.body().path("sales").path("sgst").asString())).isEqualTo(sgst);
        BinResp csv = bytes("GET", "/v1/spaces/" + b.space() + "/finance-gst/export?from=2026-02-01&to=2026-02-28", btoken(b, b.owner(), "OWNER"), null, null);
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.header("Content-Type")).startsWith("text/csv");
        assertThat(csv.header("Content-Disposition")).contains("gst-preparation-worksheet-2026-02-01-2026-02-28.csv");
        String text = csv.text();
        assertThat(text).startsWith("format,type,record_id,number,date,party,party_gstin,place_of_supply,supply_type,tax_percent,taxable_amount,cgst,sgst,igst,total_tax,status");
        assertThat(text).contains("FINORA_GST_PREPARATION_WORKSHEET_V1,SALE").contains("\"'=HYPERLINK").doesNotContain(",=HYPERLINK");
        assertThat(text.lines().count()).isEqualTo(4);
        assertThat(text).contains("EXCLUDED_NO_GST_DETAILS");
        UUID user = UUID.randomUUID();
        assertThat(bytes("GET", "/v1/spaces/" + b.space() + "/finance-gst/export", btoken(b, user, "ADMIN"), null, null).status()).as("Admin: summaries only, no export").isEqualTo(403);
        assertThat(as(b, user, "ADMIN", "GET", "/finance-gst/transactions", null).status()).as("Admin: no record-level rows").isEqualTo(403);
        assertThat(as(b, user, "ADMIN", "GET", "/finance-gst/summary", null).status()).as("Admin: aggregate summary").isEqualTo(200);
        assertThat(bytes("GET", "/v1/spaces/" + b.space() + "/finance-gst/export", btoken(b, user, "ACCOUNTANT"), null, null).status()).isEqualTo(200);
        for (String role : List.of("VIEWER", "CONTRIBUTOR", "MANAGER")) {
            assertThat(as(b, user, role, "GET", "/finance-gst/summary", null).status()).as(role).isEqualTo(403);
            assertThat(bytes("GET", "/v1/spaces/" + b.space() + "/finance-gst/export", btoken(b, user, role), null, null).status()).as(role + " export").isEqualTo(403);
        }
        Biz other = registered();
        assertThat(bytes("GET", "/v1/spaces/" + other.space() + "/finance-gst/export?from=2026-02-01&to=2026-02-28", btoken(other, other.owner(), "OWNER"), null, null).text()).as("another workspace's export holds none of it").doesNotContain("HYPERLINK").doesNotContain("1000.00");
        assertThat(raw("GET", "/v1/spaces/" + b.space() + "/finance-gst/summary", btoken(other, other.owner(), "OWNER"), null).status()).isEqualTo(404);
    }

    @Test
    void theReferenceListsWhatIsSupportedAndWhatIsNot() throws Exception {
        Biz b = biz();
        Resp r = bget(b, "/finance-gst/reference");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().path("states").path("27").asString()).isEqualTo("Maharashtra");
        assertThat(r.body().path("unsupported").toString()).contains("UTGST").contains("reverse charge").contains("input tax credit").contains("official return formats and filing");
        assertThat(r.body().path("supported").toString()).contains("INR only");
    }
}
