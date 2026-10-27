package com.finora.finance.gst;

import com.finora.commons.authz.Action;
import com.finora.commons.authz.Resource;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.business.BusinessContext;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.Validate;
import com.finora.finance.insights.Csv;
import com.finora.finance.insights.InsightsContext;
import com.finora.finance.money.Currencies;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GST-aware details and preparation worksheets for business workspaces (INR only). It records a SNAPSHOT of the tax split beside an invoice or
 * expense, built from the percentage the user typed on each line (no rate table, no HSN/SAC lookup, no exemption logic) and the explicit place
 * of supply the user states. Supported: intra-state (CGST + SGST, half each) and inter-state (IGST) supplies between states that have a
 * legislature. Not supported (refused, never guessed): union territories without a legislature (UTGST), exports, SEZ supplies, reverse charge,
 * composition scheme, input-tax-credit eligibility, e-invoicing/e-way bills, credit/debit notes, non-INR invoices. A snapshot is never
 * recalculated after the invoice is issued. This is NOT a GST return and makes no compliance claim.
 */
@Service
public class GstService {

    public static final String FORMAT = "FINORA_GST_PREPARATION_WORKSHEET_V1";
    public static final String NOTICE = "Preparation worksheet in Finora's own format. It is NOT an official GST return or any government filing format, and it has not been validated by a qualified professional.";
    private static final int MAX_ROWS = 5000;

    public record Registration(boolean gstRegistered, String gstin, String stateCode, String stateName, boolean gstinVisible) {
    }

    public record RegistrationRequest(Boolean gstRegistered, String gstin) {
    }

    public record InvoiceGstRequest(String placeOfSupply) {
    }

    public record ExpenseGstRequest(String placeOfSupply, String taxableAmount, String cgst, String sgst, String igst) {
    }

    public record Breakdown(String taxPercent, String taxableAmount, String cgst, String sgst, String igst, String totalTax) {
    }

    /** status: NOT_RECORDED, RECORDED, PREVIEW; snapshotStale: the invoice total or tax changed after the snapshot was taken (only possible while a draft). */
    public record InvoiceGst(String status, UUID invoiceId, String supplierGstin, String supplierState, String recipientGstin, String recipientState, String placeOfSupplyState,
            String placeOfSupplyName, String supplyType, String ruleVersion, boolean snapshotStale, String invoiceTotal, String invoiceTaxTotal, List<Breakdown> breakdown,
            List<String> warnings, OffsetDateTime calculatedAt) {
    }

    public record ExpenseGst(String status, UUID expenseId, String vendorGstin, String vendorState, String placeOfSupplyState, String supplyType, String taxableAmount, String cgst,
            String sgst, String igst, String totalTax, String expenseAmount, String ruleVersion, List<String> warnings) {
    }

    private final JdbcClient jdbc;
    private final FinanceAudit audit;
    private final com.finora.finance.subscriptions.Entitlements entitlements;

    public GstService(JdbcClient jdbc, FinanceAudit audit, com.finora.finance.subscriptions.Entitlements entitlements) {
        this.entitlements = entitlements;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static BusinessContext business(InsightsContext ctx) {
        if (!ctx.business()) {
            throw new ApiException(ErrorCode.CONFLICT, "GST support exists only in business workspaces");
        }
        return ctx.businessContext();
    }

    private static OffsetDateTime now(InsightsContext ctx) {
        return OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------- registration

    private record SettingsRow(boolean gstRegistered, String gstin) {
    }

    private SettingsRow settings(InsightsContext ctx) {
        jdbc.sql("insert into business_settings (tenant_id, updated_at) values (:t, :now) on conflict (tenant_id) do nothing").param("t", ctx.tenantId()).param("now", now(ctx)).update();
        return jdbc.sql("select gst_registered, gstin from business_settings where tenant_id = :t").param("t", ctx.tenantId()).query(SettingsRow.class).single();
    }

    public Registration registration(InsightsContext ctx) {
        business(ctx);
        ctx.requireRegionalRead();
        SettingsRow s = settings(ctx);
        boolean visible = true;
        try {
            ctx.requireSettingsWrite();
        } catch (RuntimeException e) {
            visible = false;
        }
        String gstin = s.gstin();
        return new Registration(s.gstRegistered(), gstin == null ? null : visible ? gstin : GstCodes.mask(gstin), gstin == null ? null : gstin.substring(0, 2),
                gstin == null ? null : GstCodes.states().get(gstin.substring(0, 2)), visible);
    }

    @Transactional
    public Registration setRegistration(InsightsContext ctx, RegistrationRequest req) {
        business(ctx);
        ctx.requireSettingsWrite();
        SettingsRow current = settings(ctx);
        boolean registered = req.gstRegistered() == null ? current.gstRegistered() : req.gstRegistered();
        String gstin = current.gstin();
        if (req.gstin() != null) {
            String g = req.gstin().trim().toUpperCase(Locale.ROOT);
            if (g.isEmpty()) {
                gstin = null;
            } else {
                String problem = GstCodes.problem(g);
                if (problem != null) {
                    throw Validate.invalid(problem);
                }
                gstin = g;
            }
        }
        if (registered && gstin == null) {
            throw Validate.invalid("A GSTIN is required for a GST-registered workspace");
        }
        jdbc.sql("update business_settings set gst_registered = :r, gstin = :g, updated_at = :now where tenant_id = :t").param("r", registered).param("g", gstin).param("now", now(ctx))
                .param("t", ctx.tenantId()).update();
        // The identifier itself is not written to the audit log; existing snapshots keep the GSTIN they were taken with.
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "GST_REGISTRATION_UPDATED", "BUSINESS_SETTINGS", ctx.tenantId(), "registered=" + registered + " gstin_set=" + (gstin != null));
        return registration(ctx);
    }

    // ---------------------------------------------------------------- invoice GST

    private record InvoiceRow(UUID id, String status, String currency, BigDecimal total, BigDecimal taxTotal, UUID clientId) {
    }

    private record LineRow(BigDecimal taxPercent, BigDecimal net, BigDecimal tax) {
    }

    private record Split(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {
    }

    static Split split(BigDecimal taxable, BigDecimal tax, boolean intra, int exponent) {
        if (!intra) {
            return new Split(taxable, BigDecimal.ZERO.setScale(exponent), BigDecimal.ZERO.setScale(exponent), tax);
        }
        BigDecimal cgst = tax.divide(BigDecimal.valueOf(2), exponent, RoundingMode.DOWN);
        return new Split(taxable, cgst, tax.subtract(cgst), BigDecimal.ZERO.setScale(exponent));
    }

    private static boolean isIntra(String supplierState, String place) {
        if (supplierState.equals(place)) {
            if (GstCodes.intraStateUnsupported(place)) {
                throw Validate.invalid("An intra-state supply in " + GstCodes.states().get(place) + " may need UTGST or special handling, which is not supported; no GST split was calculated");
            }
            return true;
        }
        return false;
    }

    public InvoiceGst invoiceGst(InsightsContext ctx, UUID invoiceId) {
        BusinessContext b = business(ctx);
        b.requireAll(Resource.INVOICES, Action.READ);
        InvoiceRow inv = invoice(ctx, invoiceId);
        return load(ctx, inv).orElse(new InvoiceGst("NOT_RECORDED", invoiceId, null, null, null, null, null, null, null, null, false, inv.total().toPlainString(), inv.taxTotal().toPlainString(), List.of(),
                List.of("No GST details have been recorded for this invoice."), null));
    }

    private InvoiceRow invoice(InsightsContext ctx, UUID id) {
        return jdbc.sql("select id, status, currency, total, tax_total, client_id from invoices where tenant_id = :t and id = :id").param("t", ctx.tenantId()).param("id", id)
                .query(InvoiceRow.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    private record DetailRow(String supplierGstin, String supplierStateCode, String recipientGstin, String recipientStateCode, String placeOfSupplyState, String supplyType, BigDecimal invoiceTotal,
            BigDecimal invoiceTaxTotal, String ruleVersion, OffsetDateTime calculatedAt) {
    }

    private record BreakdownRow(BigDecimal taxPercent, BigDecimal taxableAmount, BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal totalTax) {
    }

    private java.util.Optional<InvoiceGst> load(InsightsContext ctx, InvoiceRow inv) {
        return jdbc.sql("""
                select supplier_gstin, supplier_state_code, recipient_gstin, recipient_state_code, place_of_supply_state, supply_type, invoice_total, invoice_tax_total, rule_version, calculated_at
                from invoice_gst_details where tenant_id = :t and invoice_id = :id
                """).param("t", ctx.tenantId()).param("id", inv.id()).query(DetailRow.class).optional().map(d -> {
            String cur = inv.currency().trim();
            List<Breakdown> rows = jdbc.sql("select tax_percent, taxable_amount, cgst, sgst, igst, total_tax from invoice_gst_breakdown where tenant_id = :t and invoice_id = :id order by tax_percent")
                    .param("t", ctx.tenantId()).param("id", inv.id()).query(BreakdownRow.class).list().stream()
                    .map(r -> new Breakdown(r.taxPercent().toPlainString(), Currencies.format(r.taxableAmount(), cur), Currencies.format(r.cgst(), cur), Currencies.format(r.sgst(), cur),
                            Currencies.format(r.igst(), cur), Currencies.format(r.totalTax(), cur))).toList();
            boolean stale = d.invoiceTotal().compareTo(inv.total()) != 0 || d.invoiceTaxTotal().compareTo(inv.taxTotal()) != 0;
            List<String> warnings = new ArrayList<>();
            if (stale) {
                warnings.add("The invoice total or tax changed after these GST details were recorded. Recalculate them while the invoice is a draft; they are excluded from the GST summary until then.");
            }
            if (d.recipientGstin() == null) {
                warnings.add("No valid recipient GSTIN was recorded (unregistered or unknown recipient).");
            }
            return new InvoiceGst("RECORDED", inv.id(), d.supplierGstin(), d.supplierStateCode().trim(), d.recipientGstin(), d.recipientStateCode() == null ? null : d.recipientStateCode().trim(),
                    d.placeOfSupplyState().trim(), GstCodes.states().get(d.placeOfSupplyState().trim()), d.supplyType(), d.ruleVersion(), stale, Currencies.format(d.invoiceTotal(), cur),
                    Currencies.format(d.invoiceTaxTotal(), cur), rows, warnings, d.calculatedAt());
        });
    }

    /** Calculates (and, unless {@code preview}, saves) the GST split of an invoice from its lines and the stated place of supply. */
    @Transactional
    public InvoiceGst calculateInvoice(InsightsContext ctx, UUID invoiceId, InvoiceGstRequest req, boolean preview) {
        BusinessContext b = business(ctx);
        entitlements.require(ctx, "gst");
        b.requireAll(Resource.INVOICES, Action.UPDATE);
        SettingsRow s = settings(ctx);
        if (!s.gstRegistered() || s.gstin() == null) {
            throw new ApiException(ErrorCode.CONFLICT, "This workspace is not set up as GST-registered. Enter its GSTIN in the GST registration settings first; nothing is assumed.");
        }
        InvoiceRow inv = jdbc.sql("select id, status, currency, total, tax_total, client_id from invoices where tenant_id = :t and id = :id for update").param("t", ctx.tenantId()).param("id", invoiceId)
                .query(InvoiceRow.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
        String cur = inv.currency().trim();
        if (!"INR".equals(cur)) {
            throw Validate.invalid("GST amounts are only supported for INR invoices (this invoice is in " + cur + ")");
        }
        if ("CANCELLED".equals(inv.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "A cancelled invoice has no GST details");
        }
        boolean exists = jdbc.sql("select count(*) from invoice_gst_details where tenant_id = :t and invoice_id = :id").param("t", ctx.tenantId()).param("id", invoiceId).query(Long.class).single() > 0;
        if (!preview && "ISSUED".equals(inv.status()) && exists) {
            throw new ApiException(ErrorCode.INVOICE_IMMUTABLE, "The GST details of an issued invoice are frozen and are never recalculated");
        }
        String place = req.placeOfSupply() == null ? null : req.placeOfSupply().trim();
        if (place == null || place.isEmpty()) {
            throw Validate.invalid("place_of_supply (a two-digit state code) is required; it is never assumed");
        }
        if (!GstCodes.usablePlaceOfSupply(place)) {
            throw Validate.invalid("place_of_supply must be a known two-digit state or union-territory code (not 97 or 99)");
        }
        String supplierState = s.gstin().substring(0, 2);
        boolean intra = isIntra(supplierState, place);
        String recipientGstin = jdbc.sql("select gstin from clients where tenant_id = :t and id = :id").param("t", ctx.tenantId()).param("id", inv.clientId()).query(String.class).optional().orElse(null);
        List<String> warnings = new ArrayList<>();
        String recipientState = null;
        if (recipientGstin != null) {
            String problem = GstCodes.problem(recipientGstin);
            if (problem != null) {
                warnings.add("The client's GSTIN was not used: " + problem + ".");
                recipientGstin = null;
            } else {
                recipientState = recipientGstin.substring(0, 2);
            }
        }
        List<LineRow> lines = jdbc.sql("select tax_percent, net_amount as net, tax_amount as tax from invoice_lines where tenant_id = :t and invoice_id = :id order by position")
                .param("t", ctx.tenantId()).param("id", invoiceId).query(LineRow.class).list();
        if (lines.isEmpty()) {
            throw Validate.invalid("The invoice has no lines");
        }
        Map<BigDecimal, BigDecimal[]> byRate = new TreeMap<>();
        for (LineRow l : lines) {
            BigDecimal[] acc = byRate.computeIfAbsent(l.taxPercent(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            acc[0] = acc[0].add(l.net());
            acc[1] = acc[1].add(l.tax());
        }
        int exp = Currencies.exponentOrThrow("INR");
        List<Breakdown> rows = new ArrayList<>();
        BigDecimal taxSum = BigDecimal.ZERO;
        record Calc(BigDecimal rate, Split split, BigDecimal tax) {
        }
        List<Calc> calcs = new ArrayList<>();
        for (var e : byRate.entrySet()) {
            Split sp = split(e.getValue()[0], e.getValue()[1], intra, exp);
            calcs.add(new Calc(e.getKey(), sp, e.getValue()[1]));
            taxSum = taxSum.add(e.getValue()[1]);
            rows.add(new Breakdown(e.getKey().toPlainString(), Currencies.format(sp.taxable(), "INR"), Currencies.format(sp.cgst(), "INR"), Currencies.format(sp.sgst(), "INR"),
                    Currencies.format(sp.igst(), "INR"), Currencies.format(e.getValue()[1], "INR")));
        }
        if (taxSum.compareTo(inv.taxTotal()) != 0) {
            throw new ApiException(ErrorCode.CONFLICT, "The invoice's line taxes do not add up to its tax total, so no GST split was calculated");
        }
        if (inv.taxTotal().signum() == 0) {
            warnings.add("The invoice carries no tax. Whether it is exempt, nil-rated or outside GST is not determined here.");
        }
        String supply = intra ? "INTRA_STATE" : "INTER_STATE";
        if (preview) {
            return new InvoiceGst("PREVIEW", invoiceId, s.gstin(), supplierState, recipientGstin, recipientState, place, GstCodes.states().get(place), supply, GstCodes.RULE_VERSION, false,
                    Currencies.format(inv.total(), "INR"), Currencies.format(inv.taxTotal(), "INR"), rows, warnings, null);
        }
        jdbc.sql("delete from invoice_gst_breakdown where tenant_id = :t and invoice_id = :id").param("t", ctx.tenantId()).param("id", invoiceId).update();
        jdbc.sql("delete from invoice_gst_details where tenant_id = :t and invoice_id = :id").param("t", ctx.tenantId()).param("id", invoiceId).update();
        jdbc.sql("""
                insert into invoice_gst_details (tenant_id, invoice_id, supplier_gstin, supplier_state_code, recipient_gstin, recipient_state_code, place_of_supply_state, supply_type,
                                                 invoice_total, invoice_tax_total, rule_version, calculated_at, calculated_by)
                values (:t, :id, :sg, :ss, :rg, :rs, :pos, :st, :total, :tax, :rv, :now, :u)
                """).param("t", ctx.tenantId()).param("id", invoiceId).param("sg", s.gstin()).param("ss", supplierState).param("rg", recipientGstin).param("rs", recipientState).param("pos", place)
                .param("st", supply).param("total", inv.total()).param("tax", inv.taxTotal()).param("rv", GstCodes.RULE_VERSION).param("now", now(ctx)).param("u", ctx.userId()).update();
        for (Calc c : calcs) {
            jdbc.sql("""
                    insert into invoice_gst_breakdown (tenant_id, invoice_id, tax_percent, taxable_amount, cgst, sgst, igst, total_tax) values (:t, :id, :r, :ta, :c, :s, :i, :tt)
                    """).param("t", ctx.tenantId()).param("id", invoiceId).param("r", c.rate()).param("ta", c.split().taxable()).param("c", c.split().cgst()).param("s", c.split().sgst())
                    .param("i", c.split().igst()).param("tt", c.tax()).update();
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "INVOICE_GST_RECORDED", "INVOICE", invoiceId, "supply=" + supply + " place=" + place + " rule=" + GstCodes.RULE_VERSION);
        return invoiceGst(ctx, invoiceId);
    }

    // ---------------------------------------------------------------- expense GST (details printed on a vendor bill, entered by a person)

    private record ExpenseRow(UUID id, String status, String currency, BigDecimal amount, UUID vendorId) {
    }

    private static BigDecimal money(String field, String raw, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw Validate.invalid(field + " is required");
            }
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal v;
        try {
            v = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw Validate.invalid(field + " must be a decimal amount");
        }
        if (v.signum() < 0 || v.scale() > 2) {
            throw Validate.invalid(field + " must be zero or more with at most 2 decimals");
        }
        return v.setScale(2);
    }

    public ExpenseGst expenseGst(InsightsContext ctx, UUID expenseId) {
        BusinessContext b = business(ctx);
        b.requireAll(Resource.TRANSACTIONS, Action.READ);
        expense(ctx, expenseId);
        return jdbc.sql("""
                select vendor_gstin, vendor_state_code, place_of_supply_state, supply_type, taxable_amount, cgst, sgst, igst, total_tax, expense_amount, rule_version
                from expense_gst_details where tenant_id = :t and expense_id = :id
                """).param("t", ctx.tenantId()).param("id", expenseId).query((rs, i) -> new ExpenseGst("RECORDED", expenseId, rs.getString(1), trimOrNull(rs.getString(2)), rs.getString(3).trim(), rs.getString(4),
                Currencies.format(rs.getBigDecimal(5), "INR"), Currencies.format(rs.getBigDecimal(6), "INR"), Currencies.format(rs.getBigDecimal(7), "INR"), Currencies.format(rs.getBigDecimal(8), "INR"),
                Currencies.format(rs.getBigDecimal(9), "INR"), Currencies.format(rs.getBigDecimal(10), "INR"), rs.getString(11), List.of())).optional()
                .orElse(new ExpenseGst("NOT_RECORDED", expenseId, null, null, null, null, null, null, null, null, null, null, null, List.of("No GST details have been recorded for this expense.")));
    }

    private static String trimOrNull(String s) {
        return s == null ? null : s.trim();
    }

    private ExpenseRow expense(InsightsContext ctx, UUID id) {
        return jdbc.sql("select id, status, currency, amount, vendor_id from business_expenses where tenant_id = :t and id = :id").param("t", ctx.tenantId()).param("id", id)
                .query(ExpenseRow.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    @Transactional
    public ExpenseGst recordExpense(InsightsContext ctx, UUID expenseId, ExpenseGstRequest req) {
        BusinessContext b = business(ctx);
        entitlements.require(ctx, "gst");
        b.requireAll(Resource.TRANSACTIONS, Action.UPDATE);
        SettingsRow s = settings(ctx);
        if (!s.gstRegistered() || s.gstin() == null) {
            throw new ApiException(ErrorCode.CONFLICT, "This workspace is not set up as GST-registered. Enter its GSTIN in the GST registration settings first; nothing is assumed.");
        }
        ExpenseRow e = jdbc.sql("select id, status, currency, amount, vendor_id from business_expenses where tenant_id = :t and id = :id for update").param("t", ctx.tenantId()).param("id", expenseId)
                .query(ExpenseRow.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
        if (!"INR".equals(e.currency().trim())) {
            throw Validate.invalid("GST amounts are only supported for INR expenses (this expense is in " + e.currency().trim() + ")");
        }
        if (!"POSTED".equals(e.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "A voided expense has no GST details");
        }
        String place = req.placeOfSupply() == null ? null : req.placeOfSupply().trim();
        if (place == null || !GstCodes.usablePlaceOfSupply(place)) {
            throw Validate.invalid("place_of_supply must be a known two-digit state or union-territory code (not 97 or 99); it is never assumed");
        }
        BigDecimal taxable = money("taxable_amount", req.taxableAmount(), true);
        BigDecimal cgst = money("cgst", req.cgst(), false);
        BigDecimal sgst = money("sgst", req.sgst(), false);
        BigDecimal igst = money("igst", req.igst(), false);
        BigDecimal total = cgst.add(sgst).add(igst);
        if (taxable.add(total).compareTo(e.amount()) != 0) {
            throw Validate.invalid("taxable_amount plus the GST amounts must equal the expense amount " + Currencies.format(e.amount(), "INR"));
        }
        String vendorGstin = e.vendorId() == null ? null
                : jdbc.sql("select gstin from vendors where tenant_id = :t and id = :id").param("t", ctx.tenantId()).param("id", e.vendorId()).query(String.class).optional().orElse(null);
        List<String> warnings = new ArrayList<>();
        String vendorState = null;
        String supply = null;
        if (vendorGstin != null && GstCodes.problem(vendorGstin) != null) {
            warnings.add("The vendor's GSTIN was not used: " + GstCodes.problem(vendorGstin) + ".");
            vendorGstin = null;
        }
        if (vendorGstin == null) {
            if (total.signum() != 0) {
                throw Validate.invalid("GST was entered but the vendor has no valid GSTIN on record; add the vendor's GSTIN first, or enter zero GST");
            }
            warnings.add("The vendor has no valid GSTIN, so no supply type was determined.");
        } else {
            vendorState = vendorGstin.substring(0, 2);
            boolean intra = isIntra(vendorState, place);
            supply = intra ? "INTRA_STATE" : "INTER_STATE";
            if (intra && igst.signum() != 0) {
                throw Validate.invalid("An intra-state supply carries CGST and SGST, not IGST");
            }
            if (!intra && (cgst.signum() != 0 || sgst.signum() != 0)) {
                throw Validate.invalid("An inter-state supply carries IGST, not CGST or SGST");
            }
            if (intra && cgst.compareTo(sgst) != 0) {
                throw Validate.invalid("CGST and SGST must be equal for an intra-state supply");
            }
        }
        jdbc.sql("delete from expense_gst_details where tenant_id = :t and expense_id = :id").param("t", ctx.tenantId()).param("id", expenseId).update();
        jdbc.sql("""
                insert into expense_gst_details (tenant_id, expense_id, vendor_gstin, vendor_state_code, place_of_supply_state, supply_type, taxable_amount, cgst, sgst, igst, total_tax,
                                                 expense_amount, rule_version, recorded_at, recorded_by)
                values (:t, :id, :vg, :vs, :pos, :st, :ta, :c, :s, :i, :tt, :ea, :rv, :now, :u)
                """).param("t", ctx.tenantId()).param("id", expenseId).param("vg", vendorGstin).param("vs", vendorState).param("pos", place).param("st", supply).param("ta", taxable).param("c", cgst)
                .param("s", sgst).param("i", igst).param("tt", total).param("ea", e.amount()).param("rv", GstCodes.RULE_VERSION).param("now", now(ctx)).param("u", ctx.userId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "EXPENSE_GST_RECORDED", "EXPENSE", expenseId, "supply=" + supply + " place=" + place + " rule=" + GstCodes.RULE_VERSION);
        ExpenseGst saved = expenseGst(ctx, expenseId);
        return new ExpenseGst(saved.status(), saved.expenseId(), saved.vendorGstin(), saved.vendorState(), saved.placeOfSupplyState(), saved.supplyType(), saved.taxableAmount(), saved.cgst(),
                saved.sgst(), saved.igst(), saved.totalTax(), saved.expenseAmount(), saved.ruleVersion(), warnings);
    }

    // ---------------------------------------------------------------- summary, transactions, export

    private record SaleRow(UUID id, String number, LocalDate invoiceDate, String currency, BigDecimal total, BigDecimal taxTotal, BigDecimal snapTotal, BigDecimal snapTax, String supplyType,
            String recipientGstin, String placeOfSupplyState, String clientName) {
    }

    private record PurchaseRow(UUID id, LocalDate expenseDate, String currency, BigDecimal amount, String description, String vendorName, String vendorGstin, String placeOfSupplyState,
            String supplyType, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal totalTax, boolean recorded) {
    }

    private record Period(LocalDate from, LocalDate to) {
    }

    private Period period(InsightsContext ctx, String fromRaw, String toRaw) {
        LocalDate from = Validate.date("from", fromRaw, false);
        LocalDate to = Validate.date("to", toRaw, false);
        if (from == null && to == null) {
            YearMonth last = YearMonth.from(ctx.today()).minusMonths(1);
            return new Period(last.atDay(1), last.atEndOfMonth());
        }
        if (from == null || to == null) {
            throw Validate.invalid("Give both from and to, or neither (the last complete month is used)");
        }
        if (from.isAfter(to) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 1100) {
            throw Validate.invalid("from must not be after to, and the period can span at most about 3 years");
        }
        return new Period(from, to);
    }

    private List<SaleRow> sales(InsightsContext ctx, Period p) {
        return jdbc.sql("""
                select i.id, i.number, i.invoice_date, i.currency, i.total, i.tax_total, d.invoice_total as snap_total, d.invoice_tax_total as snap_tax, d.supply_type, d.recipient_gstin,
                       d.place_of_supply_state, c.name as client_name
                from invoices i left join invoice_gst_details d on d.tenant_id = i.tenant_id and d.invoice_id = i.id
                left join clients c on c.tenant_id = i.tenant_id and c.id = i.client_id
                where i.tenant_id = :t and i.status = 'ISSUED' and i.invoice_date between :f and :to order by i.invoice_date, i.number limit %d
                """.formatted(MAX_ROWS)).param("t", ctx.tenantId()).param("f", p.from()).param("to", p.to()).query(SaleRow.class).list();
    }

    private List<PurchaseRow> purchases(InsightsContext ctx, Period p) {
        return jdbc.sql("""
                select e.id, e.expense_date, e.currency, e.amount, e.description, v.name as vendor_name, g.vendor_gstin, g.place_of_supply_state, g.supply_type, g.taxable_amount as taxable,
                       g.cgst, g.sgst, g.igst, g.total_tax, (g.expense_id is not null) as recorded
                from business_expenses e left join expense_gst_details g on g.tenant_id = e.tenant_id and g.expense_id = e.id
                left join vendors v on v.tenant_id = e.tenant_id and v.id = e.vendor_id
                where e.tenant_id = :t and e.status = 'POSTED' and e.expense_date between :f and :to order by e.expense_date, e.id limit %d
                """.formatted(MAX_ROWS)).param("t", ctx.tenantId()).param("f", p.from()).param("to", p.to()).query(PurchaseRow.class).list();
    }

    private static String saleStatus(SaleRow r) {
        if (!"INR".equals(r.currency().trim())) {
            return "EXCLUDED_NON_INR";
        }
        if (r.supplyType() == null) {
            return "EXCLUDED_NO_GST_DETAILS";
        }
        if (r.snapTotal().compareTo(r.total()) != 0 || r.snapTax().compareTo(r.taxTotal()) != 0) {
            return "EXCLUDED_STALE_SNAPSHOT";
        }
        return "INCLUDED";
    }

    private static String purchaseStatus(PurchaseRow r) {
        if (!"INR".equals(r.currency().trim())) {
            return "EXCLUDED_NON_INR";
        }
        return r.recorded() ? "INCLUDED" : "EXCLUDED_NO_GST_DETAILS";
    }

    private static String inr(BigDecimal v) {
        return Currencies.format(v.setScale(2, RoundingMode.HALF_EVEN), "INR");
    }

    public Map<String, Object> summary(InsightsContext ctx, String fromRaw, String toRaw) {
        business(ctx);
        entitlements.require(ctx, "gst");
        ctx.requireReports();
        Period p = period(ctx, fromRaw, toRaw);
        Registration reg = registration(ctx);
        Map<String, BigDecimal[]> salesByMonth = new TreeMap<>(); // taxable, cgst, sgst, igst
        BigDecimal[] sales = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal issuedTax = BigDecimal.ZERO;
        BigDecimal includedInvoiceTax = BigDecimal.ZERO;
        BigDecimal excludedTax = BigDecimal.ZERO;
        int included = 0;
        Map<String, Integer> excluded = new TreeMap<>();
        Map<String, Integer> nonInr = new TreeMap<>();
        for (SaleRow r : sales(ctx, p)) {
            String st = saleStatus(r);
            if ("EXCLUDED_NON_INR".equals(st)) {
                nonInr.merge(r.currency().trim(), 1, Integer::sum);
                continue;
            }
            issuedTax = issuedTax.add(r.taxTotal());
            if (!"INCLUDED".equals(st)) {
                excluded.merge(st, 1, Integer::sum);
                excludedTax = excludedTax.add(r.taxTotal());
                continue;
            }
            included++;
            includedInvoiceTax = includedInvoiceTax.add(r.taxTotal());
        }
        record B(YearMonth m, BigDecimal t, BigDecimal c, BigDecimal s, BigDecimal i) {
        }
        for (B b : jdbc.sql("""
                select date_trunc('month', i.invoice_date)::date as d, sum(b.taxable_amount), sum(b.cgst), sum(b.sgst), sum(b.igst)
                from invoices i join invoice_gst_details d on d.tenant_id = i.tenant_id and d.invoice_id = i.id
                join invoice_gst_breakdown b on b.tenant_id = d.tenant_id and b.invoice_id = d.invoice_id
                where i.tenant_id = :t and i.status = 'ISSUED' and i.currency = 'INR' and i.invoice_date between :f and :to and d.invoice_total = i.total and d.invoice_tax_total = i.tax_total
                group by 1 order by 1
                """).param("t", ctx.tenantId()).param("f", p.from()).param("to", p.to())
                .query((rs, n) -> new B(YearMonth.from(rs.getDate(1).toLocalDate()), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5))).list()) {
            salesByMonth.put(b.m().toString(), new BigDecimal[] {b.t(), b.c(), b.s(), b.i()});
            for (int k = 0; k < 4; k++) {
                sales[k] = sales[k].add(new BigDecimal[] {b.t(), b.c(), b.s(), b.i()}[k]);
            }
        }
        BigDecimal salesTax = sales[1].add(sales[2]).add(sales[3]);
        BigDecimal[] buy = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal incAmount = BigDecimal.ZERO;
        BigDecimal exAmount = BigDecimal.ZERO;
        BigDecimal allAmount = BigDecimal.ZERO;
        int pIncluded = 0;
        Map<String, Integer> pExcluded = new TreeMap<>();
        Map<String, Integer> pNonInr = new TreeMap<>();
        Map<String, BigDecimal[]> buyByMonth = new TreeMap<>();
        for (PurchaseRow r : purchases(ctx, p)) {
            String st = purchaseStatus(r);
            if ("EXCLUDED_NON_INR".equals(st)) {
                pNonInr.merge(r.currency().trim(), 1, Integer::sum);
                continue;
            }
            allAmount = allAmount.add(r.amount());
            if (!"INCLUDED".equals(st)) {
                pExcluded.merge(st, 1, Integer::sum);
                exAmount = exAmount.add(r.amount());
                continue;
            }
            pIncluded++;
            incAmount = incAmount.add(r.amount());
            BigDecimal[] m = buyByMonth.computeIfAbsent(YearMonth.from(r.expenseDate()).toString(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] vals = {r.taxable(), r.cgst(), r.sgst(), r.igst()};
            for (int k = 0; k < 4; k++) {
                m[k] = m[k].add(vals[k]);
                buy[k] = buy[k].add(vals[k]);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", FORMAT);
        out.put("official_filing_format", false);
        out.put("notice", NOTICE);
        out.put("currency", "INR");
        out.put("period", Map.of("from", p.from().toString(), "to", p.to().toString()));
        SettingsRow raw = settings(ctx);
        Map<String, Object> regOut = new LinkedHashMap<>();
        regOut.put("gst_registered", raw.gstRegistered());
        regOut.put("gstin_masked", raw.gstin() == null ? null : GstCodes.mask(raw.gstin()));
        regOut.put("state_name", reg.stateName());
        out.put("registration", regOut);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("invoices_included", included);
        s.put("taxable_amount", inr(sales[0]));
        s.put("cgst", inr(sales[1]));
        s.put("sgst", inr(sales[2]));
        s.put("igst", inr(sales[3]));
        s.put("total_tax", inr(salesTax));
        s.put("by_month", monthly(salesByMonth));
        s.put("excluded_invoices", excluded);
        s.put("excluded_non_inr_invoices", nonInr);
        out.put("sales", s);
        Map<String, Object> pu = new LinkedHashMap<>();
        pu.put("expenses_included", pIncluded);
        pu.put("taxable_amount", inr(buy[0]));
        pu.put("cgst", inr(buy[1]));
        pu.put("sgst", inr(buy[2]));
        pu.put("igst", inr(buy[3]));
        pu.put("total_tax", inr(buy[1].add(buy[2]).add(buy[3])));
        pu.put("by_month", monthly(buyByMonth));
        pu.put("excluded_expenses", pExcluded);
        pu.put("excluded_non_inr_expenses", pNonInr);
        pu.put("note", "Tax charged on purchases as printed on the bills you entered. Whether any of it is claimable as input tax credit is NOT determined here.");
        out.put("purchases", pu);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("issued_inr_invoice_tax_total", inr(issuedTax));
        rec.put("included_invoice_tax_total", inr(includedInvoiceTax));
        rec.put("excluded_invoice_tax_total", inr(excludedTax));
        rec.put("sales_breakdown_tax_total", inr(salesTax));
        rec.put("sales_reconciled", salesTax.compareTo(includedInvoiceTax) == 0 && includedInvoiceTax.add(excludedTax).compareTo(issuedTax) == 0);
        rec.put("purchase_amount_total_inr", inr(allAmount));
        rec.put("purchase_amount_included", inr(incAmount));
        rec.put("purchase_amount_excluded", inr(exAmount));
        rec.put("purchases_reconciled", incAmount.add(exAmount).compareTo(allAmount) == 0 && buy[0].add(buy[1]).add(buy[2]).add(buy[3]).compareTo(incAmount) == 0);
        out.put("reconciliation", rec);
        List<String> flags = new ArrayList<>();
        if (!reg.gstRegistered()) {
            flags.add("This workspace is not set up as GST-registered; only records with stored GST details are summarised.");
        }
        excluded.forEach((k, v) -> flags.add(v + " issued invoice(s) excluded: " + k));
        nonInr.forEach((k, v) -> flags.add(v + " " + k + " invoice(s) excluded because GST amounts are INR only"));
        pExcluded.forEach((k, v) -> flags.add(v + " expense(s) excluded: " + k));
        out.put("flags", flags);
        out.put("limitations", List.of("Tax rates are the percentages typed on invoice lines; no rate table, HSN/SAC or exemption logic is applied.",
                "Unsupported (not calculated): UTGST, exports, SEZ, reverse charge, composition scheme, input-tax-credit eligibility, credit/debit notes, e-invoicing.",
                "Sales use the invoice date and purchases the expense date as the period date; the legal time of supply may differ.",
                "CGST is half the tax rounded down to the paisa and SGST is the remainder, so CGST + SGST always equals the tax exactly."));
        return out;
    }

    private static List<Map<String, String>> monthly(Map<String, BigDecimal[]> by) {
        List<Map<String, String>> out = new ArrayList<>();
        by.forEach((m, v) -> out.add(Map.of("month", m, "taxable_amount", inr(v[0]), "cgst", inr(v[1]), "sgst", inr(v[2]), "igst", inr(v[3]), "total_tax", inr(v[1].add(v[2]).add(v[3])))));
        return out;
    }

    public Map<String, Object> transactions(InsightsContext ctx, String fromRaw, String toRaw) {
        business(ctx);
        entitlements.require(ctx, "gst");
        ctx.requireFullReports();
        Period p = period(ctx, fromRaw, toRaw);
        return Map.of("format", FORMAT, "notice", NOTICE, "period", Map.of("from", p.from().toString(), "to", p.to().toString()), "rows", rows(ctx, p));
    }

    private List<Map<String, String>> rows(InsightsContext ctx, Period p) {
        List<Map<String, String>> out = new ArrayList<>();
        for (SaleRow r : sales(ctx, p)) {
            String st = saleStatus(r);
            if ("INCLUDED".equals(st)) {
                for (BreakdownRow b : jdbc.sql("select tax_percent, taxable_amount, cgst, sgst, igst, total_tax from invoice_gst_breakdown where tenant_id = :t and invoice_id = :id order by tax_percent")
                        .param("t", ctx.tenantId()).param("id", r.id()).query(BreakdownRow.class).list()) {
                    out.add(row("SALE", r.id(), r.number(), r.invoiceDate(), r.clientName(), r.recipientGstin(), r.placeOfSupplyState(), r.supplyType(), b.taxPercent().toPlainString(),
                            b.taxableAmount(), b.cgst(), b.sgst(), b.igst(), b.totalTax(), st));
                }
            } else {
                out.add(row("SALE", r.id(), r.number(), r.invoiceDate(), r.clientName(), r.recipientGstin(), r.placeOfSupplyState(), r.supplyType(), "", null, null, null, null, null, st));
            }
        }
        for (PurchaseRow r : purchases(ctx, p)) {
            String st = purchaseStatus(r);
            out.add(row("PURCHASE", r.id(), "", r.expenseDate(), r.vendorName(), r.vendorGstin(), r.placeOfSupplyState(), r.supplyType(), "", r.taxable(), r.cgst(), r.sgst(), r.igst(),
                    r.totalTax(), st));
        }
        return out;
    }

    private static Map<String, String> row(String type, UUID id, String number, LocalDate date, String party, String gstin, String place, String supply, String rate, BigDecimal taxable,
            BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal total, String status) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("record_id", id.toString());
        m.put("number", number == null ? "" : number);
        m.put("date", date.toString());
        m.put("party", party == null ? "" : party);
        m.put("party_gstin", gstin == null ? "" : gstin);
        m.put("place_of_supply", place == null ? "" : place.trim());
        m.put("supply_type", supply == null ? "" : supply);
        m.put("tax_percent", rate);
        m.put("taxable_amount", taxable == null ? "" : inr(taxable));
        m.put("cgst", cgst == null ? "" : inr(cgst));
        m.put("sgst", sgst == null ? "" : inr(sgst));
        m.put("igst", igst == null ? "" : inr(igst));
        m.put("total_tax", total == null ? "" : inr(total));
        m.put("status", status);
        return m;
    }

    public String exportCsv(InsightsContext ctx, String fromRaw, String toRaw) {
        business(ctx);
        entitlements.require(ctx, "gst");
        ctx.requireExport();
        Period p = period(ctx, fromRaw, toRaw);
        List<Map<String, String>> rows = rows(ctx, p);
        StringBuilder out = new StringBuilder();
        List<String> head = new ArrayList<>(List.of("format"));
        head.addAll(List.of("type", "record_id", "number", "date", "party", "party_gstin", "place_of_supply", "supply_type", "tax_percent", "taxable_amount", "cgst", "sgst", "igst", "total_tax", "status"));
        Csv.row(out, head);
        for (Map<String, String> r : rows) {
            List<String> cells = new ArrayList<>(List.of(FORMAT));
            cells.addAll(r.values());
            Csv.row(out, cells);
        }
        audit.record(ctx.tenantId(), ctx.userId(), ctx.clock(), "GST_WORKSHEET_EXPORTED", "GST", null, "from " + p.from() + " to " + p.to() + " rows=" + rows.size());
        return out.toString();
    }

    public static String fileName(String from, String to) {
        return "gst-preparation-worksheet-" + from + "-" + to + ".csv";
    }

    public Map<String, Object> reference(InsightsContext ctx) {
        ctx.requireRegionalRead();
        return Map.of("rule_version", GstCodes.RULE_VERSION, "states", GstCodes.states(), "unsupported_intra_state_codes", List.of("04", "25", "26", "31", "35", "38", "97", "99"),
                "supported", List.of("CGST + SGST split for intra-state supply", "IGST for inter-state supply", "INR only", "tax percent as typed on the invoice line"),
                "unsupported", List.of("UTGST", "exports and SEZ", "reverse charge", "composition scheme", "input tax credit eligibility", "credit/debit notes", "e-invoicing and e-way bills",
                        "rate tables, HSN/SAC", "exemption classification", "official return formats and filing"));
    }
}
