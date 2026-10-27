"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import { businessAccess, PROJECT_STATUSES, type Project, type ProjectDetail } from "@/lib/business";
import { CURRENCIES, formatMoney, validateAmountText } from "@/lib/finance";
import { useFinance, useIntentKey } from "@/lib/FinanceContext";
import { usePaged } from "@/lib/usePaged";
import type { Role } from "@/lib/types";
import type { Lookups } from "./lookups";
import { Notice } from "../Notice";
import { ListState, Pager } from "./Common";

const label = (s: string) => s.charAt(0) + s.slice(1).toLowerCase().replace("_", " ");

export function ProjectsPanel({ role, lookups, baseCurrency, onChanged }: { role: Role; lookups: Lookups; baseCurrency: string; onChanged: () => void }) {
  const finance = useFinance();
  const write = businessAccess.write(role);
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const list = usePaged<Project>("/projects", { q, status });
  const [selected, setSelected] = useState<ProjectDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const intent = useIntentKey();
  const [form, setForm] = useState({ name: "", client: "", currency: baseCurrency, budget: "" });

  async function open(id: string) {
    setError(null);
    try {
      setSelected(await finance.get<ProjectDetail>(`/projects/${id}`));
    } catch (e) {
      setError(describeError(e));
    }
  }

  async function create(event: FormEvent) {
    event.preventDefault();
    if (!form.name.trim()) return setError("Enter a project name.");
    if (form.budget.trim()) {
      const problem = validateAmountText(form.budget, form.currency);
      if (problem) return setError(`Budget: ${problem}`);
    }
    setError(null);
    try {
      const created = await finance.send<ProjectDetail>("POST", "/projects", {
        name: form.name.trim(), client_id: form.client || undefined, currency: form.currency, budget_amount: form.budget.trim() || undefined,
      }, intent.key());
      intent.done();
      setSelected(created);
      setForm({ ...form, name: "", budget: "" });
      list.reload();
      onChanged();
    } catch (e) {
      setError(describeError(e));
    }
  }

  async function change(p: ProjectDetail, body: Record<string, unknown>) {
    setError(null);
    try {
      setSelected(await finance.send<ProjectDetail>("PATCH", `/projects/${p.id}`, { ...body, version: p.version }));
      list.reload();
      onChanged();
    } catch (e) {
      setError(describeError(e));
    }
  }

  async function archive(p: ProjectDetail) {
    setError(null);
    try {
      setSelected(await finance.send<ProjectDetail>("POST", `/projects/${p.id}/${p.archived ? "restore" : "archive"}`, {}));
      list.reload();
      onChanged();
    } catch (e) {
      setError(describeError(e));
    }
  }

  const prof = selected?.profitability;
  return (
    <>
      {error && <Notice>{error}</Notice>}
      <div className="filters" role="search" aria-label="Filter projects">
        <div className="column"><label htmlFor="proj-q">Search</label><input id="proj-q" value={q} onChange={(e) => setQ(e.target.value)} /></div>
        <div className="column">
          <label htmlFor="proj-status">Status</label>
          <select id="proj-status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">All</option>
            {PROJECT_STATUSES.map((s) => <option key={s} value={s}>{label(s)}</option>)}
          </select>
        </div>
      </div>
      <ListState loading={list.loading} error={list.error} empty={list.items.length === 0} onRetry={list.reload}
        emptyText={q || status ? "No projects match these filters." : `No projects yet.${write ? " Create one below to track its budget and profit." : ""}`}>
        <table>
          <caption className="visually-hidden">Projects</caption>
          <thead><tr><th scope="col">Project</th><th scope="col">Client</th><th scope="col">Status</th><th scope="col">Budget</th><th scope="col"><span className="visually-hidden">Actions</span></th></tr></thead>
          <tbody>
            {list.items.map((p) => (
              <tr key={p.id}>
                <td>{p.name}{p.archived && " (archived)"}</td>
                <td>{p.client_name ?? "—"}</td>
                <td>{label(p.status)}</td>
                <td>{p.budget_amount ? formatMoney(p.budget_amount, p.currency) : "—"}</td>
                <td><button type="button" className="link" onClick={() => void open(p.id)}>View {p.name}</button></td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pager total={list.total} offset={list.offset} onGo={list.goTo} />
      </ListState>

      {selected && prof && (
        <section className="card column" aria-label="Project details">
          <h2>{selected.name}</h2>
          <p className="muted">{label(selected.status)}{selected.client_name && ` · ${selected.client_name}`} · {selected.currency}</p>
          <h3>Profitability (all time)</h3>
          <p>Revenue (invoiced, excluding tax) {formatMoney(prof.revenue_net, prof.currency)} − Costs {formatMoney(prof.costs, prof.currency)} = <strong>Profit {formatMoney(prof.profit, prof.currency)}</strong>
            {prof.margin_percent !== null ? ` (${prof.margin_percent}% margin)` : " (no margin: no revenue yet)"}</p>
          <p>Invoiced incl. tax {formatMoney(prof.invoiced_total, prof.currency)} · Received {formatMoney(prof.received, prof.currency)} · Outstanding {formatMoney(prof.outstanding, prof.currency)}</p>
          {prof.budget && (
            <p>Budget {formatMoney(prof.budget, prof.currency)}: {prof.budget_used_percent}% used, {prof.over_budget ? <strong>over budget by {formatMoney(prof.budget_remaining!.replace("-", ""), prof.currency)}</strong> : <>{formatMoney(prof.budget_remaining!, prof.currency)} left</>}</p>
          )}
          {write && (
            <div className="row">
              <label htmlFor="proj-set-status">Status</label>
              <select id="proj-set-status" value={selected.status} onChange={(e) => void change(selected, { status: e.target.value })} disabled={selected.archived}>
                {PROJECT_STATUSES.map((s) => <option key={s} value={s}>{label(s)}</option>)}
              </select>
              <button type="button" className="link" onClick={() => void archive(selected)}>{selected.archived ? "Restore" : "Archive"}</button>
            </div>
          )}
        </section>
      )}

      {write && (
        <form onSubmit={create} aria-labelledby="proj-form-title" noValidate>
          <h2 id="proj-form-title">New project</h2>
          <label htmlFor="proj-name">Name</label>
          <input id="proj-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <label htmlFor="proj-client">Client (optional)</label>
          <select id="proj-client" value={form.client} onChange={(e) => setForm({ ...form, client: e.target.value })}>
            <option value="">No client</option>
            {lookups.clients.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          <label htmlFor="proj-currency">Currency</label>
          <select id="proj-currency" value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })}>
            {CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
          <label htmlFor="proj-budget">Cost budget (optional)</label>
          <input id="proj-budget" value={form.budget} onChange={(e) => setForm({ ...form, budget: e.target.value })} inputMode="decimal" />
          <button type="submit">Create project</button>
        </form>
      )}
    </>
  );
}
