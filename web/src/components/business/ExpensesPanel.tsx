"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import { businessAccess, type Expense } from "@/lib/business";
import { CURRENCIES, formatMoney, todayLocal, validateAmountText } from "@/lib/finance";
import { useFinance, useIntentKey } from "@/lib/FinanceContext";
import { usePaged } from "@/lib/usePaged";
import type { Role } from "@/lib/types";
import type { Lookups } from "./lookups";
import { Notice } from "../Notice";
import { ListState, Pager } from "./Common";

const empty = { date: "", amount: "", currency: "INR", description: "", reference: "", vendor: "", project: "", category: "" };

export function ExpensesPanel({ role, lookups, baseCurrency, onChanged }: { role: Role; lookups: Lookups; baseCurrency: string; onChanged: () => void }) {
  const finance = useFinance();
  const [filters, setFilters] = useState({ q: "", from: "", to: "", vendor_id: "", project_id: "", category_id: "", voided: false });
  const list = usePaged<Expense>("/expenses", {
    q: filters.q, from: filters.from, to: filters.to, vendor_id: filters.vendor_id, project_id: filters.project_id, category_id: filters.category_id,
    include_voided: filters.voided ? "true" : "",
  });
  const intent = useIntentKey();
  const [form, setForm] = useState({ ...empty, currency: baseCurrency });
  const [editing, setEditing] = useState<Expense | null>(null);
  const [voiding, setVoiding] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [newCategory, setNewCategory] = useState("");
  const canCreate = businessAccess.expensesCreate(role);
  const write = businessAccess.write(role);

  function startEdit(e: Expense) {
    setEditing(e);
    setForm({ date: e.expense_date, amount: e.amount, currency: e.currency, description: e.description, reference: e.reference ?? "", vendor: e.vendor_id ?? "", project: e.project_id ?? "", category: e.category_id ?? "" });
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSuccess(null);
    if (!form.description.trim()) return setError("Describe the expense.");
    if (!/^\d{4}-\d{2}-\d{2}$/.test(form.date)) return setError("Choose the expense date.");
    const problem = validateAmountText(form.amount, form.currency);
    if (problem) return setError(`Amount: ${problem}`);
    setError(null);
    try {
      if (editing) {
        await finance.send("PATCH", `/expenses/${editing.id}`, {
          version: editing.version, expense_date: form.date, amount: form.amount.trim(), description: form.description.trim(), reference: form.reference.trim(),
          vendor_id: form.vendor || undefined, clear_vendor: !form.vendor, project_id: form.project || undefined, clear_project: !form.project,
          category_id: form.category || undefined, clear_category: !form.category,
        });
        setEditing(null);
        setSuccess("Expense updated.");
      } else {
        await finance.send("POST", "/expenses", {
          expense_date: form.date, amount: form.amount.trim(), currency: form.currency, description: form.description.trim(), reference: form.reference.trim() || undefined,
          vendor_id: form.vendor || undefined, project_id: form.project || undefined, category_id: form.category || undefined,
        }, intent.key());
        intent.done();
        setSuccess("Expense recorded.");
      }
      setForm({ ...empty, currency: form.currency });
      list.reload();
      onChanged();
    } catch (e) {
      setError(describeError(e));
    }
  }

  async function voidIt(e: Expense) {
    if (!reason.trim()) return setError("Say why you are voiding this expense.");
    setError(null);
    try {
      await finance.send("POST", `/expenses/${e.id}/void`, { reason: reason.trim(), version: e.version });
      setVoiding(null);
      setReason("");
      setSuccess("Expense voided. It no longer counts in any total.");
      list.reload();
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function addCategory(event: FormEvent) {
    event.preventDefault();
    if (!newCategory.trim()) return;
    try {
      await finance.send("POST", "/expense-categories", { name: newCategory.trim() });
      setNewCategory("");
      onChanged();
    } catch (e) {
      setError(describeError(e));
    }
  }

  const set = (key: keyof typeof filters, value: string | boolean) => setFilters((f) => ({ ...f, [key]: value }));
  return (
    <>
      {error && <Notice>{error}</Notice>}
      {success && <Notice kind="success">{success}</Notice>}
      <div className="filters" role="search" aria-label="Filter expenses">
        <div className="column"><label htmlFor="exp-q">Search</label><input id="exp-q" value={filters.q} onChange={(e) => set("q", e.target.value)} /></div>
        <div className="column"><label htmlFor="exp-from">From</label><input id="exp-from" type="date" value={filters.from} onChange={(e) => set("from", e.target.value)} /></div>
        <div className="column"><label htmlFor="exp-to">To</label><input id="exp-to" type="date" value={filters.to} onChange={(e) => set("to", e.target.value)} /></div>
        {lookups.projects.length > 0 && (
          <div className="column">
            <label htmlFor="exp-project-f">Project</label>
            <select id="exp-project-f" value={filters.project_id} onChange={(e) => set("project_id", e.target.value)}>
              <option value="">All</option>
              {lookups.projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </div>
        )}
        <label><input type="checkbox" checked={filters.voided} onChange={(e) => set("voided", e.target.checked)} /> Show voided</label>
      </div>
      <ListState loading={list.loading} error={list.error} empty={list.items.length === 0} onRetry={list.reload}
        emptyText={filters.q || filters.from || filters.to || filters.project_id ? "No expenses match these filters." : "No expenses yet. Record one below."}>
        <table>
          <caption className="visually-hidden">Expenses</caption>
          <thead><tr><th scope="col">Date</th><th scope="col">Details</th><th scope="col">Amount</th><th scope="col">Actions</th></tr></thead>
          <tbody>
            {list.items.map((x) => (
              <tr key={x.id} className={x.status === "VOIDED" ? "voided" : undefined}>
                <td>{x.expense_date}</td>
                <td>
                  {x.description}
                  <div className="muted">{[x.vendor_name, x.project_name, x.category_name, x.reference].filter(Boolean).join(" · ")}{x.status === "VOIDED" && ` · voided: ${x.void_reason}`}</div>
                </td>
                <td>{formatMoney(x.amount, x.currency)}</td>
                <td>
                  {x.status === "POSTED" && canCreate && <button type="button" className="link" onClick={() => startEdit(x)}>Edit</button>}
                  {x.status === "POSTED" && write && <button type="button" className="link" onClick={() => setVoiding(voiding === x.id ? null : x.id)}>Void</button>}
                  {voiding === x.id && (
                    <div className="card column">
                      <label htmlFor={`void-${x.id}`}>Reason</label>
                      <input id={`void-${x.id}`} value={reason} onChange={(e) => setReason(e.target.value)} />
                      <button type="button" onClick={() => void voidIt(x)}>Confirm void</button>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pager total={list.total} offset={list.offset} onGo={list.goTo} />
      </ListState>

      {canCreate && (
        <form onSubmit={submit} aria-labelledby="exp-form-title" noValidate>
          <h2 id="exp-form-title">{editing ? "Edit expense" : "Record an expense"}</h2>
          <label htmlFor="exp-date">Date</label>
          <input id="exp-date" type="date" value={form.date} max={todayLocal()} onChange={(e) => setForm({ ...form, date: e.target.value })} />
          <label htmlFor="exp-amount">Amount</label>
          <input id="exp-amount" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} inputMode="decimal" />
          <label htmlFor="exp-currency">Currency</label>
          <select id="exp-currency" value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })} disabled={!!editing}>
            {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
          <label htmlFor="exp-desc">Description</label>
          <input id="exp-desc" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
          {lookups.vendors.length > 0 && (
            <>
              <label htmlFor="exp-vendor">Vendor (optional)</label>
              <select id="exp-vendor" value={form.vendor} onChange={(e) => setForm({ ...form, vendor: e.target.value })}>
                <option value="">No vendor</option>
                {lookups.vendors.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
              </select>
            </>
          )}
          {lookups.projects.length > 0 && (
            <>
              <label htmlFor="exp-project">Project (optional; same currency)</label>
              <select id="exp-project" value={form.project} onChange={(e) => setForm({ ...form, project: e.target.value })}>
                <option value="">No project</option>
                {lookups.projects.map((p) => <option key={p.id} value={p.id}>{p.name} ({p.currency})</option>)}
              </select>
            </>
          )}
          <label htmlFor="exp-category">Category (optional)</label>
          <select id="exp-category" value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })}>
            <option value="">Uncategorised</option>
            {lookups.categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          <label htmlFor="exp-ref">Vendor bill reference (optional)</label>
          <input id="exp-ref" value={form.reference} onChange={(e) => setForm({ ...form, reference: e.target.value })} />
          <button type="submit">{editing ? "Save changes" : "Record expense"}</button>
          {editing && <button type="button" className="link" onClick={() => { setEditing(null); setForm({ ...empty, currency: baseCurrency }); }}>Cancel</button>}
        </form>
      )}
      {write && (
        <form onSubmit={addCategory} aria-labelledby="cat-form-title" noValidate>
          <h2 id="cat-form-title">Add expense category</h2>
          <label htmlFor="exp-newcat">Name</label>
          <input id="exp-newcat" value={newCategory} onChange={(e) => setNewCategory(e.target.value)} />
          <button type="submit">Add category</button>
        </form>
      )}
    </>
  );
}
