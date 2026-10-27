"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import { businessAccess, type PartyDetail, type Party } from "@/lib/business";
import { formatMoney } from "@/lib/finance";
import { useFinance, useIntentKey } from "@/lib/FinanceContext";
import { usePaged } from "@/lib/usePaged";
import type { Role } from "@/lib/types";
import { Notice } from "../Notice";
import { ListState, Pager } from "./Common";

/** Clients or vendors: list with search and paging, a detail card, create, edit and archive. */
export function PartiesPanel({ kind, role, onChanged }: { kind: "clients" | "vendors"; role: Role; onChanged: () => void }) {
  const finance = useFinance();
  const noun = kind === "clients" ? "client" : "vendor";
  const [q, setQ] = useState("");
  const [archived, setArchived] = useState(false);
  const list = usePaged<Party>(`/${kind}`, { q, include_archived: archived ? "true" : "" });
  const [selected, setSelected] = useState<PartyDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const write = businessAccess.write(role);
  const intent = useIntentKey();
  const [form, setForm] = useState({ name: "", email: "", phone: "", gstin: "", address: "", terms: "" });

  function setField(key: keyof typeof form, value: string) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  async function open(id: string) {
    setError(null);
    setEditing(false);
    try {
      setSelected(await finance.get<PartyDetail>(`/${kind}/${id}`));
    } catch (e) {
      setError(describeError(e));
    }
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!form.name.trim()) return setError(`Enter the ${noun}'s name.`);
    if (kind === "clients" && form.terms.trim() && !/^\d{1,3}$/.test(form.terms.trim())) return setError("Payment terms are a whole number of days.");
    setError(null);
    const body: Record<string, unknown> = { name: form.name.trim(), email: form.email.trim(), phone: form.phone.trim(), gstin: form.gstin.trim(), address: form.address.trim() };
    if (kind === "clients" && form.terms.trim()) body.payment_terms_days = Number(form.terms);
    try {
      if (editing && selected) {
        setSelected(await finance.send<PartyDetail>("PATCH", `/${kind}/${selected.id}`, { ...body, version: selected.version }));
        setEditing(false);
      } else {
        const created = await finance.send<PartyDetail>("POST", `/${kind}`, body, intent.key());
        intent.done();
        setSelected(created);
        setForm({ name: "", email: "", phone: "", gstin: "", address: "", terms: "" });
      }
      list.reload();
      onChanged();
    } catch (e) {
      setError(describeError(e));
    }
  }

  async function toggleArchive(p: PartyDetail) {
    setError(null);
    try {
      setSelected(await finance.send<PartyDetail>("POST", `/${kind}/${p.id}/${p.archived ? "restore" : "archive"}`, {}));
      list.reload();
      onChanged();
    } catch (e) {
      setError(describeError(e));
    }
  }

  function startEdit(p: PartyDetail) {
    setEditing(true);
    setForm({ name: p.name, email: p.email ?? "", phone: p.phone ?? "", gstin: p.gstin ?? "", address: p.address ?? "", terms: p.payment_terms_days?.toString() ?? "" });
  }

  return (
    <>
      {error && <Notice>{error}</Notice>}
      <div className="filters" role="search" aria-label={`Search ${kind}`}>
        <div className="column">
          <label htmlFor={`${kind}-q`}>Search</label>
          <input id={`${kind}-q`} value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name or email" />
        </div>
        <label><input type="checkbox" checked={archived} onChange={(e) => setArchived(e.target.checked)} /> Show archived</label>
      </div>
      <ListState loading={list.loading} error={list.error} empty={list.items.length === 0} onRetry={list.reload}
        emptyText={q ? `No ${kind} match “${q}”.` : `No ${kind} yet.${write ? ` Add your first ${noun} below.` : ""}`}>
        <table>
          <caption className="visually-hidden">{kind}</caption>
          <thead><tr><th scope="col">Name</th><th scope="col">Email</th><th scope="col">GSTIN</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
          <tbody>
            {list.items.map((p) => (
              <tr key={p.id}>
                <td>{p.name}{p.archived && " (archived)"}</td>
                <td>{p.email ?? "—"}</td>
                <td>{p.gstin ?? "—"}</td>
                <td><button type="button" className="link" onClick={() => void open(p.id)}>View {p.name}</button></td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pager total={list.total} offset={list.offset} onGo={list.goTo} />
      </ListState>

      {selected && (
        <section className="card column" aria-label={`${noun} details`}>
          <h2>{selected.name}{selected.archived && " (archived)"}</h2>
          <p className="muted">{[selected.email, selected.phone, selected.gstin && `GSTIN ${selected.gstin}`].filter(Boolean).join(" · ") || "No contact details"}</p>
          {selected.address && <p>{selected.address}</p>}
          {kind === "clients" && selected.payment_terms_days !== null && <p>Payment terms: {selected.payment_terms_days} days</p>}
          {selected.balances?.length === 0 && <p>No issued invoices yet.</p>}
          {selected.balances?.map((b) => (
            <p key={b.currency}>
              Invoiced {formatMoney(b.invoiced, b.currency)} · Paid {formatMoney(b.paid, b.currency)} · <strong>Outstanding {formatMoney(b.outstanding, b.currency)}</strong>
              {b.overdue !== "0.00" && b.overdue !== "0" && ` · Overdue ${formatMoney(b.overdue, b.currency)}`}
            </p>
          ))}
          {selected.unallocated_payments?.map((u) => <p key={u.currency} className="muted">Unallocated payments (advance credit): {formatMoney(u.amount, u.currency)}</p>)}
          {selected.expenses?.length === 0 && <p>No expenses yet.</p>}
          {selected.expenses?.map((x) => <p key={x.currency}>Total expenses: {formatMoney(x.amount, x.currency)}</p>)}
          {write && (
            <div className="row">
              {!selected.archived && <button type="button" onClick={() => startEdit(selected)}>Edit</button>}
              <button type="button" className="link" onClick={() => void toggleArchive(selected)}>{selected.archived ? "Restore" : "Archive"}</button>
            </div>
          )}
        </section>
      )}

      {write && (
        <form onSubmit={save} aria-labelledby={`${kind}-form-title`} noValidate>
          <h2 id={`${kind}-form-title`}>{editing ? `Edit ${noun}` : `Add ${noun}`}</h2>
          <label htmlFor={`${kind}-name`}>Name</label>
          <input id={`${kind}-name`} value={form.name} onChange={(e) => setField("name", e.target.value)} />
          <label htmlFor={`${kind}-email`}>Email (optional)</label>
          <input id={`${kind}-email`} value={form.email} onChange={(e) => setField("email", e.target.value)} />
          <label htmlFor={`${kind}-phone`}>Phone (optional)</label>
          <input id={`${kind}-phone`} value={form.phone} onChange={(e) => setField("phone", e.target.value)} />
          <label htmlFor={`${kind}-gstin`}>GSTIN (optional; format is checked, not verified)</label>
          <input id={`${kind}-gstin`} value={form.gstin} onChange={(e) => setField("gstin", e.target.value)} />
          <label htmlFor={`${kind}-address`}>Address (optional)</label>
          <textarea id={`${kind}-address`} value={form.address} onChange={(e) => setField("address", e.target.value)} />
          {kind === "clients" && (
            <>
              <label htmlFor="client-terms">Payment terms in days (optional)</label>
              <input id="client-terms" value={form.terms} onChange={(e) => setField("terms", e.target.value)} inputMode="numeric" />
            </>
          )}
          <button type="submit">{editing ? "Save changes" : `Add ${noun}`}</button>
          {editing && <button type="button" className="link" onClick={() => setEditing(false)}>Cancel</button>}
        </form>
      )}
    </>
  );
}
