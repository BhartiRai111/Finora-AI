"use client";

import { useState } from "react";
import { describeError } from "@/lib/api";
import { useFinance } from "@/lib/FinanceContext";
import { monthName, rateProblem, type RateStatus, type RateView, type RegionalSettings, formatDateText } from "@/lib/regional";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";
import { ReasonDialog } from "../ui/Dialog";

/** Suggestions only: engines list canonical names (Asia/Calcutta), so common names are added and the server stays the judge. */
const ZONES = [...new Set([...(typeof Intl !== "undefined" && "supportedValuesOf" in Intl ? (Intl as unknown as { supportedValuesOf: (k: string) => string[] }).supportedValuesOf("timeZone") : []), "UTC", "Asia/Kolkata"])];

function SettingsForm({ settings, canWrite, onSaved }: { settings: RegionalSettings; canWrite: boolean; onSaved: () => void }) {
  const finance = useFinance();
  const [form, setForm] = useState({
    timezone: settings.timezone,
    display_currency: settings.display_currency ?? "",
    locale: settings.locale ?? "",
    date_format: settings.date_format ?? "",
    fiscal: String(settings.fiscal_year_start_month),
    period: settings.reporting_period,
    stale: String(settings.rate_stale_after_days),
  });
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const set = (patch: Partial<typeof form>) => {
    setSaved(false);
    setForm((f) => ({ ...f, ...patch }));
  };

  async function save() {
    setSaved(false);
    if (!/^(UTC|[A-Za-z_]+(\/[A-Za-z0-9_+-]+)+)$/.test(form.timezone.trim())) return setError("Enter a time zone name such as Asia/Kolkata (Area/City).");
    if (!/^\d+$/.test(form.stale) || Number(form.stale) > 365) return setError("Stale-rate threshold must be a whole number of days from 0 to 365.");
    setError(null);
    setBusy(true);
    try {
      await finance.send("PUT", "/finance-regional", {
        timezone: form.timezone.trim(),
        display_currency: form.display_currency || undefined,
        clear_display_currency: form.display_currency === "" ? true : undefined,
        locale: form.locale || undefined,
        clear_locale: form.locale === "" ? true : undefined,
        date_format: form.date_format || undefined,
        clear_date_format: form.date_format === "" ? true : undefined,
        fiscal_year_start_month: Number(form.fiscal),
        reporting_period: form.period,
        rate_stale_after_days: Number(form.stale),
      });
      setSaved(true);
      onSaved();
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form aria-label="Regional settings" onSubmit={(e) => { e.preventDefault(); void save(); }}>
      <h2>Regional settings</h2>
      <p className="muted">These only change how values are shown and where period boundaries fall. Stored amounts, currencies and dates are never changed. Today in this workspace: {formatDateText(settings.today, settings.date_format)}.</p>
      {!canWrite && <Notice kind="info">You can view these settings but not change them.</Notice>}
      {error && <Notice>{error}</Notice>}
      {saved && <Notice kind="success">Settings saved.</Notice>}
      <div className="filters">
        <div className="column">
          <label htmlFor="rg-tz">Time zone</label>
          <input id="rg-tz" list="rg-zones" value={form.timezone} onChange={(e) => set({ timezone: e.target.value })} disabled={!canWrite} />
          <datalist id="rg-zones">{ZONES.map((z) => <option key={z} value={z} />)}</datalist>
        </div>
        <div className="column">
          <label htmlFor="rg-cur">Display currency</label>
          <select id="rg-cur" value={form.display_currency} onChange={(e) => set({ display_currency: e.target.value })} disabled={!canWrite}>
            <option value="">No preference</option>
            {settings.supported.currencies.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
        </div>
        <div className="column">
          <label htmlFor="rg-loc">Number format (locale)</label>
          <select id="rg-loc" value={form.locale} onChange={(e) => set({ locale: e.target.value })} disabled={!canWrite}>
            <option value="">Default</option>
            {settings.supported.locales.map((l) => <option key={l} value={l}>{l}</option>)}
          </select>
        </div>
        <div className="column">
          <label htmlFor="rg-date">Date format</label>
          <select id="rg-date" value={form.date_format} onChange={(e) => set({ date_format: e.target.value })} disabled={!canWrite}>
            <option value="">Default (YYYY-MM-DD)</option>
            <option value="ISO">YYYY-MM-DD</option>
            <option value="DMY">DD/MM/YYYY</option>
            <option value="MDY">MM/DD/YYYY</option>
          </select>
        </div>
        <div className="column">
          <label htmlFor="rg-fy">Fiscal year starts in</label>
          <select id="rg-fy" value={form.fiscal} onChange={(e) => set({ fiscal: e.target.value })} disabled={!canWrite}>
            {Array.from({ length: 12 }, (_, i) => i + 1).map((m) => <option key={m} value={m}>{monthName(m)}</option>)}
          </select>
        </div>
        <div className="column">
          <label htmlFor="rg-period">Default reporting period</label>
          <select id="rg-period" value={form.period} onChange={(e) => set({ period: e.target.value as RegionalSettings["reporting_period"] })} disabled={!canWrite}>
            <option value="MONTH">Month</option>
            <option value="QUARTER">Quarter</option>
            <option value="FISCAL_YEAR">Fiscal year</option>
          </select>
        </div>
        <div className="column">
          <label htmlFor="rg-stale">Treat a rate as stale after (days)</label>
          <input id="rg-stale" inputMode="numeric" value={form.stale} onChange={(e) => set({ stale: e.target.value })} disabled={!canWrite} />
        </div>
        {canWrite && <button type="submit" disabled={busy}>{busy ? "Saving…" : "Save settings"}</button>}
      </div>
    </form>
  );
}

function Rates({ settings, status }: { settings: RegionalSettings; status: RateStatus }) {
  const finance = useFinance();
  const rates = useResource((signal) => finance.get<{ items: RateView[] }>("/finance-rates?include_voided=true&limit=100", signal));
  const [form, setForm] = useState({ base: "USD", quote: settings.base_currency ?? "INR", rate: "", date: settings.today, note: "" });
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const canWrite = status.manual_entry.can_write;

  async function add() {
    setDone(null);
    const problem = rateProblem(form.rate);
    if (form.base === form.quote) return setError("The two currencies must be different.");
    if (problem) return setError(problem);
    if (!form.date) return setError("Choose the date the rate applies to.");
    setError(null);
    setBusy(true);
    try {
      await finance.send("POST", "/finance-rates", { base_currency: form.base, quote_currency: form.quote, rate: form.rate.trim(), rate_date: form.date, source_note: form.note.trim() || undefined });
      setForm((f) => ({ ...f, rate: "", note: "" }));
      setDone("Rate saved. It is recorded as user-supplied.");
      rates.reload();
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  const [voiding, setVoiding] = useState<string | null>(null);

  async function voidRate(id: string, reason: string) {
    setError(null);
    try {
      await finance.send("POST", `/finance-rates/${id}/void`, { reason });
      rates.reload();
    } catch (e) {
      throw new Error(describeError(e));
    }
  }

  return (
    <section aria-labelledby="rates-title">
      <h2 id="rates-title">Exchange rates</h2>
      <Notice kind="info">{status.automatic_source.message} Rates here are entered by a person; each use of a rate shows its date and source. A rate older than {status.stale_after_days} day(s) is treated as stale and is not applied unless you accept it.</Notice>
      {error && <Notice>{error}</Notice>}
      {done && <Notice kind="success">{done}</Notice>}
      {canWrite ? (
        <form aria-label="Add an exchange rate" onSubmit={(e) => { e.preventDefault(); void add(); }}>
          <div className="filters">
            <div className="column">
              <label htmlFor="rt-base">From (1 unit of)</label>
              <select id="rt-base" value={form.base} onChange={(e) => setForm({ ...form, base: e.target.value })}>{settings.supported.currencies.map((c) => <option key={c}>{c}</option>)}</select>
            </div>
            <div className="column">
              <label htmlFor="rt-quote">To (equals)</label>
              <select id="rt-quote" value={form.quote} onChange={(e) => setForm({ ...form, quote: e.target.value })}>{settings.supported.currencies.map((c) => <option key={c}>{c}</option>)}</select>
            </div>
            <div className="column"><label htmlFor="rt-rate">Rate</label><input id="rt-rate" inputMode="decimal" value={form.rate} onChange={(e) => setForm({ ...form, rate: e.target.value })} placeholder="83.2150" /></div>
            <div className="column"><label htmlFor="rt-date">Rate date</label><input id="rt-date" type="date" value={form.date} max={settings.today} onChange={(e) => setForm({ ...form, date: e.target.value })} /></div>
            <div className="column"><label htmlFor="rt-note">Where it came from (optional)</label><input id="rt-note" maxLength={200} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} /></div>
            <button type="submit" disabled={busy}>{busy ? "Saving…" : "Add rate"}</button>
          </div>
        </form>
      ) : (
        <Notice kind="info">You can view exchange rates but not enter them.</Notice>
      )}
      {rates.status === "loading" && <Loading label="Loading rates…" />}
      {rates.status === "error" && (
        <>
          <Notice>{describeError(rates.error)}</Notice>
          <button type="button" onClick={rates.reload}>Try again</button>
        </>
      )}
      {rates.status === "ready" && rates.data.items.length === 0 && <p>No exchange rates have been entered, so reports stay in each original currency and nothing is converted.</p>}
      {rates.status === "ready" && rates.data.items.length > 0 && (
        <table>
          <caption>Entered rates</caption>
          <thead><tr><th scope="col">Pair</th><th scope="col">Rate</th><th scope="col">Date</th><th scope="col">Source</th><th scope="col">Status</th>{canWrite && <th scope="col">Action</th>}</tr></thead>
          <tbody>
            {rates.data.items.map((r) => (
              <tr key={r.id} className={r.status === "VOIDED" ? "muted" : undefined}>
                <td>1 {r.base_currency} = {r.quote_currency}</td>
                <td>{r.rate}</td>
                <td>{formatDateText(r.rate_date, settings.date_format)}</td>
                <td>{r.source === "USER_SUPPLIED" ? "User-supplied" : r.source}{r.source_note ? ` (${r.source_note})` : ""}</td>
                <td>{r.status === "VOIDED" ? `Voided: ${r.void_reason ?? ""}` : "Active"}</td>
                {canWrite && <td>{r.status === "ACTIVE" && <button type="button" onClick={() => setVoiding(r.id)}>Void</button>}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <ReasonDialog open={voiding !== null} title="Void this exchange rate" description="A voided rate is kept for the record but is never used for conversion." label="Reason" required confirmLabel="Void rate" danger
        onConfirm={(reason) => voidRate(voiding!, reason)} onClose={() => setVoiding(null)} />
    </section>
  );
}

type PeriodsView = { today: string; fiscal_year_start_month: number; periods: Record<string, { from: string; to: string; end: string }>; notes: string[] };

/** The fiscal periods the server derives from the fiscal-year setting, so people can see which dates "this fiscal year" means. */
function FiscalPeriods() {
  const finance = useFinance();
  const periods = useResource((signal) => finance.get<PeriodsView>("/finance-regional/periods", signal).catch(() => null));
  if (periods.status !== "ready" || !periods.data) return null;
  const d = periods.data;
  return (
    <section aria-labelledby="fy-title">
      <h2 id="fy-title">Fiscal periods</h2>
      <table>
        <caption>Periods as of {d.today}, with the fiscal year starting in {monthName(d.fiscal_year_start_month)}</caption>
        <thead><tr><th scope="col">Period</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Last day</th></tr></thead>
        <tbody>{Object.entries(d.periods).map(([k, r]) => <tr key={k}><th scope="row">{k.replace(/_/g, " ").toLowerCase().replace(/^./, (c) => c.toUpperCase())}</th><td>{r.from}</td><td>{r.to}</td><td>{r.end}</td></tr>)}</tbody>
      </table>
      {d.notes.map((n) => <p key={n} className="muted">{n}</p>)}
    </section>
  );
}

/** Regional settings and exchange rates of the current workspace. The server decides who may change them. */
export function RegionalPanel() {
  const finance = useFinance();
  const settings = useResource((signal) => finance.get<RegionalSettings>("/finance-regional", signal));
  const status = useResource((signal) => finance.get<RateStatus>("/finance-rates/status", signal));
  if (settings.status === "loading" || status.status === "loading") return <Loading label="Loading regional settings…" />;
  if (settings.status === "error" || status.status === "error") {
    const error = settings.status === "error" ? settings.error : status.status === "error" ? status.error : null;
    return (
      <>
        <Notice>{describeError(error)}</Notice>
        <button type="button" onClick={() => { settings.reload(); status.reload(); }}>Try again</button>
      </>
    );
  }
  return (
    <>
      <SettingsForm settings={settings.data} canWrite={status.data.manual_entry.can_write} onSaved={settings.reload} />
      <FiscalPeriods />
      <Rates settings={settings.data} status={status.data} />
    </>
  );
}
