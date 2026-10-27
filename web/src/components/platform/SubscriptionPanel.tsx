"use client";

import { useEffect, useState } from "react";
import { describeError } from "@/lib/api";
import { useFinance } from "@/lib/FinanceContext";
import { formatMoney } from "@/lib/finance";
import { humanizeType, type BillingOverview, type BillingRecord, type Plan, type Subscription, type SubscriptionEvent } from "@/lib/platform";
import { useResource } from "@/lib/useResource";
import { Loading, Notice } from "../Notice";

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

function BillingSection({ isOwner }: { isOwner: boolean }) {
  const finance = useFinance();
  const overview = useResource((signal) => finance.get<BillingOverview>("/finance-billing", signal));
  const records = useResource((signal) => finance.get<{ items: BillingRecord[]; total: number }>("/finance-billing/records?limit=20", signal));
  const [name, setName] = useState<string | null>(null);
  const [email, setEmail] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  if (overview.status === "loading") return <Loading label="Loading billing…" />;
  if (overview.status === "error") return <><Notice>{describeError(overview.error)}</Notice><button type="button" onClick={overview.reload}>Try again</button></>;
  const o = overview.data;
  const nameValue = name ?? o.contact.contact_name ?? "";
  const emailValue = email ?? o.contact.contact_email ?? "";

  async function save() {
    setSaved(false);
    if (emailValue.trim() !== "" && !EMAIL.test(emailValue.trim())) return setError("Enter a valid email address for billing notices.");
    if (nameValue.length > 120) return setError("The contact name can have at most 120 characters.");
    setError(null);
    try {
      await finance.send("PUT", "/finance-billing/contact", { contact_name: nameValue.trim(), contact_email: emailValue.trim() });
      setSaved(true);
      overview.reload();
    } catch (e) {
      setError(describeError(e));
    }
  }

  return (
    <section aria-labelledby="billing-title">
      <h3 id="billing-title">Billing</h3>
      <Notice kind="info">{o.message}</Notice>
      <ul>{o.notes.map((n) => <li key={n}>{n}</li>)}</ul>
      <form aria-label="Billing contact" noValidate onSubmit={(e) => { e.preventDefault(); void save(); }}>
        {error && <Notice>{error}</Notice>}
        {saved && <Notice kind="success">Billing contact saved. Never enter card details here; none are accepted.</Notice>}
        <div className="filters">
          <div className="column"><label htmlFor="bill-name">Billing contact name</label><input id="bill-name" value={nameValue} disabled={!isOwner} maxLength={120} onChange={(e) => setName(e.target.value)} /></div>
          <div className="column"><label htmlFor="bill-email">Billing contact email</label><input id="bill-email" type="email" value={emailValue} disabled={!isOwner} onChange={(e) => setEmail(e.target.value)} /></div>
          {isOwner ? <button type="submit">Save contact</button> : <Notice kind="info">Only the workspace Owner can change the billing contact.</Notice>}
        </div>
      </form>
      <h4>Billing history</h4>
      {records.status === "loading" && <Loading label="Loading billing history…" />}
      {records.status === "error" && <><Notice>{describeError(records.error)}</Notice><button type="button" onClick={records.reload}>Try again</button></>}
      {records.status === "ready" && records.data.items.length === 0 && <p>There are no billing records. No payment provider is configured, so none is created and nothing has been charged.</p>}
      {records.status === "ready" && records.data.items.length > 0 && (
        <table>
          <caption>Billing records (application records, not tax invoices or payment receipts)</caption>
          <thead><tr><th scope="col">Date</th><th scope="col">Description</th><th scope="col">Amount</th><th scope="col">Record status</th><th scope="col">Payment</th></tr></thead>
          <tbody>
            {records.data.items.map((r) => (
              <tr key={r.id}>
                <td>{r.created_at.slice(0, 10)}</td>
                <td>{r.description}</td>
                <td>{formatMoney(r.amount, r.currency)}</td>
                <td>{humanizeType(r.status)}</td>
                <td>{r.payment_confirmed ? `Confirmed by ${r.provider} (reference ${r.provider_reference})` : r.status === "PAYMENT_FAILED" ? `Payment failed${r.payment_attempts[0] ? ` (${r.payment_attempts[0].failure_code})` : ""}` : "Not confirmed"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

/** The workspace's plan, subscription status, entitlements and billing. Entitlements are enforced by the server; this screen only shows them. */
export function SubscriptionPanel({ isOwner, canSeeAdmin }: { isOwner: boolean; canSeeAdmin: boolean }) {
  const finance = useFinance();
  const sub = useResource((signal) => finance.get<Subscription>("/finance-subscription", signal));
  const plans = useResource((signal) => finance.get<{ items: Plan[] }>("/finance-subscription/plans", signal));
  const [history, setHistory] = useState<SubscriptionEvent[] | null>(null);
  const [choice, setChoice] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!canSeeAdmin) return;
    const controller = new AbortController();
    finance.get<{ items: SubscriptionEvent[] }>("/finance-subscription/history?limit=10", controller.signal).then((h) => {
      if (!controller.signal.aborted) setHistory(h.items);
    }).catch(() => {
      if (!controller.signal.aborted) setHistory(null);
    });
    return () => controller.abort();
  }, [finance, canSeeAdmin, sub.status]);

  if (sub.status === "loading") return <Loading label="Loading your plan…" />;
  if (sub.status === "error") return <><Notice>{describeError(sub.error)}</Notice><button type="button" onClick={sub.reload}>Try again</button></>;
  const s = sub.data;

  async function act(path: string, body: unknown) {
    setBusy(true);
    setError(null);
    try {
      await finance.send("POST", path, body);
      sub.reload();
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <section aria-labelledby="plan-title">
        <h2 id="plan-title">Plan and subscription</h2>
        {error && <Notice>{error}</Notice>}
        <p>
          Current plan: <strong>{s.plan.name}</strong>. Status: <strong>{humanizeType(s.status)}</strong>.
          {s.implicit_default && " This workspace has not chosen a plan, so the default applies."}
        </p>
        {s.notices.map((n) => <Notice key={n} kind={s.status === "ACTIVE" ? "info" : "error"}>{n}</Notice>)}
        <table>
          <caption>What this plan includes</caption>
          <thead><tr><th scope="col">Feature</th><th scope="col">Available now</th><th scope="col">Limit</th></tr></thead>
          <tbody>
            {s.effective.map((e) => (
              <tr key={e.key}>
                <th scope="row">{humanizeType(e.key)}</th>
                <td>{e.enabled ? "Yes" : `No${e.reason ? `: ${e.reason}` : ""}`}</td>
                <td>{e.limit === null ? "No limit" : e.limit}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="muted">Your financial records are never deleted or hidden because of a plan or subscription change.</p>
        {isOwner ? (
          <div className="filters" role="group" aria-label="Change plan">
            {plans.status === "ready" && (
              <div className="column">
                <label htmlFor="plan-choice">Switch plan</label>
                <select id="plan-choice" value={choice} onChange={(e) => setChoice(e.target.value)}>
                  <option value="">Choose a plan</option>
                  {plans.data.items.filter((p) => !p.requires_payment).map((p) => <option key={p.plan_key} value={p.plan_key}>{p.name}</option>)}
                </select>
              </div>
            )}
            <button type="button" disabled={busy || !choice || s.status !== "ACTIVE"} onClick={() => void act("/finance-subscription/change", { plan_key: choice })}>Switch plan</button>
            {s.status === "ACTIVE" || s.status === "PAST_DUE" ? (
              <button type="button" disabled={busy} onClick={() => { const reason = window.prompt("Why are you cancelling? (optional)"); if (reason !== null) void act("/finance-subscription/cancel", { reason: reason || undefined }); }}>Cancel subscription</button>
            ) : null}
            {s.status === "CANCELLED" && <button type="button" disabled={busy} onClick={() => void act("/finance-subscription/reactivate", {})}>Reactivate</button>}
          </div>
        ) : (
          <Notice kind="info">Only the workspace Owner can change the plan or cancel the subscription.</Notice>
        )}
        {plans.status === "ready" && (
          <details>
            <summary>Available plans</summary>
            <ul>{plans.data.items.map((p) => <li key={p.plan_key}><strong>{p.name}</strong>: {p.description}{p.provisional ? " (provisional, no price)" : ""}</li>)}</ul>
          </details>
        )}
        {history && history.length > 0 && (
          <details>
            <summary>Subscription history</summary>
            <ul>{history.map((h) => <li key={h.id}>{h.created_at.slice(0, 10)}: {h.from_status ?? "none"} to {h.to_status}, plan {h.to_plan} ({h.source === "OWNER" ? "by the Owner" : "by a verified billing event"}){h.reason ? `: ${h.reason}` : ""}</li>)}</ul>
          </details>
        )}
      </section>
      <BillingSection isOwner={isOwner} />
    </>
  );
}
