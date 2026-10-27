import { humanize } from "@/lib/insights";
import { STATUS_TEXT, formatAmountLocale, formatDateText, type Conversion } from "@/lib/regional";

/**
 * A report shown in a reporting currency, always BESIDE the original per-currency report. Every converted figure lists the rate, its date and its
 * source; a currency without a usable rate says so and contributes to no combined total. Nothing is calculated here.
 */
export function ConvertedView({ conversion, locale, dateFormat }: { conversion: Conversion; locale: string | null; dateFormat: "ISO" | "DMY" | "MDY" | null }) {
  const to = conversion.reporting_currency;
  const money = (v: string) => formatAmountLocale(v, to, locale);
  return (
    <section aria-label={`Converted to ${to}`}>
      <h3>Converted to {to}</h3>
      <p className="muted">The original amounts above are unchanged. These are derived values: {conversion.method}.</p>
      {conversion.currencies.map((c) => (
        <div key={c.currency} className="card column" role="group" aria-label={`${c.currency} converted to ${to}`}>
          <h4>{c.currency} → {to}: {STATUS_TEXT[c.status]}</h4>
          {c.message && <p role={c.status === "UNAVAILABLE" ? "alert" : undefined}>{c.message}</p>}
          {c.status !== "UNAVAILABLE" && c.rows.length > 0 && (
            <table>
              <caption>Converted by period, with the rate used</caption>
              <thead>
                <tr><th scope="col">Period</th><th scope="col">Rate</th><th scope="col">Rate date</th><th scope="col">Source</th>{Object.keys(c.rows[0]!.values).map((k) => <th key={k} scope="col">{humanize(k)}</th>)}</tr>
              </thead>
              <tbody>
                {c.rows.map((r) => (
                  <tr key={r.period}>
                    <th scope="row">{r.period}</th>
                    <td>{r.rate.status === "SAME" ? "1 (same currency)" : r.rate.rate}{r.rate.status === "STALE" ? " (stale)" : ""}</td>
                    <td>{r.rate.rate_date ? formatDateText(r.rate.rate_date, dateFormat) : "—"}{r.rate.age_days ? ` (${r.rate.age_days} days old)` : ""}</td>
                    <td>{r.rate.source === "USER_SUPPLIED" ? "User-supplied" : r.rate.source === "NONE" ? "—" : r.rate.source}</td>
                    {Object.keys(c.rows[0]!.values).map((k) => <td key={k}>{r.values[k] === undefined ? "—" : money(r.values[k]!)}</td>)}
                  </tr>
                ))}
                {Object.keys(c.totals).length > 0 && (
                  <tr>
                    <th scope="row">Total</th><td colSpan={3}>{c.totals_rate?.rate ? `at ${c.totals_rate.rate} dated ${c.totals_rate.rate_date}` : "sum of the converted periods"}</td>
                    {Object.keys(c.rows[0]!.values).map((k) => <td key={k}><strong>{c.totals[k] === undefined ? "—" : money(c.totals[k]!)}</strong></td>)}
                  </tr>
                )}
              </tbody>
            </table>
          )}
          {c.status !== "UNAVAILABLE" && c.rows.length === 0 && Object.keys(c.totals).length > 0 && (
            <ul>{Object.entries(c.totals).map(([k, v]) => <li key={k}>{humanize(k)}: {money(v)}{c.totals_rate?.rate ? ` (rate ${c.totals_rate.rate} dated ${c.totals_rate.rate_date})` : ""}</li>)}</ul>
          )}
        </div>
      ))}
      {conversion.combined_totals ? (
        <div className="card column" role="group" aria-label="Combined total">
          <h4>Combined total in {to}</h4>
          <ul>{Object.entries(conversion.combined_totals).map(([k, v]) => <li key={k}>{humanize(k)}: <strong>{money(v)}</strong></li>)}</ul>
        </div>
      ) : (
        conversion.combined_unavailable_reason && <p role="status">{conversion.combined_unavailable_reason}</p>
      )}
      <details><summary>How conversion works</summary><ul>{conversion.notes.map((n) => <li key={n}>{n}</li>)}</ul></details>
    </section>
  );
}
