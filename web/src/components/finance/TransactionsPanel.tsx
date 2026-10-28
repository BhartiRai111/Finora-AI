"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { api, describeError } from "@/lib/api";
import { formatMoney, todayLocal, validateAmountText, type Account, type Category, type Transaction, type TransactionPage } from "@/lib/finance";
import { useFinance, useIntentKey } from "@/lib/FinanceContext";
import { compareAmounts } from "@/lib/decimal";
import { onDataChanged } from "@/lib/dataEvents";
import { Loading, Notice } from "../Notice";
import { ReasonDialog } from "../ui/Dialog";

type Kind = "EXPENSE" | "INCOME" | "TRANSFER";

export function TransactionsPanel({ accounts, categories, onChanged }: { accounts: Account[]; categories: Category[]; onChanged: () => void }) {
  const finance = useFinance();
  const active = accounts.filter((a) => !a.archived);
  // filters
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [accountFilter, setAccountFilter] = useState("");
  const [kindFilter, setKindFilter] = useState("");
  // The header search opens this screen with ?q=… already filled in.
  const [search, setSearch] = useState(() => (typeof window === "undefined" ? "" : (new URLSearchParams(window.location.search).get("q") ?? "").slice(0, 100)));
  const [showVoided, setShowVoided] = useState(false);
  // data
  const [items, setItems] = useState<Transaction[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const requestId = useRef(0);
  const [sort, setSort] = useState<{ by: "date" | "amount"; dir: "asc" | "desc" }>({ by: "date", dir: "desc" });
  const [voiding, setVoiding] = useState<Transaction | null>(null);

  const query = useCallback(
    (after: string | null) => {
      const p = new URLSearchParams({ limit: "25" });
      if (from) p.set("from", from);
      if (to) p.set("to", to);
      if (accountFilter) p.set("account_id", accountFilter);
      if (kindFilter) p.set("kind", kindFilter);
      if (search.trim()) p.set("q", search.trim());
      if (showVoided) p.set("include_voided", "true");
      if (after) p.set("cursor", after);
      return `/transactions?${p.toString()}`;
    },
    [from, to, accountFilter, kindFilter, search, showVoided],
  );

  useEffect(() => {
    const id = ++requestId.current;
    const controller = new AbortController();
    api<TransactionPage>(`/v1/spaces/${finance.space}${query(null)}`, { signal: controller.signal })
      .then((page) => {
        if (id !== requestId.current) return;
        setItems(page.items);
        setCursor(page.next_cursor);
        setLoadError(null);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (id !== requestId.current || controller.signal.aborted) return;
        setLoadError(describeError(e));
        setLoading(false);
      });
    return () => controller.abort();
  }, [finance.space, query, tick]);

  // A change saved elsewhere (the Add expense dialog, the calendar) shows up here without a reload.
  useEffect(() => onDataChanged(() => setTick((n) => n + 1)), []);

  // Sorting applies to the rows loaded so far (the server returns newest first and offers no other order).
  const rows = useMemo(() => {
    if (sort.by === "date" && sort.dir === "desc") return items;
    const sorted = [...items].sort((a, b) => (sort.by === "date" ? a.occurred_on.localeCompare(b.occurred_on) : a.currency === b.currency ? compareAmounts(a.amount, b.amount) : a.currency.localeCompare(b.currency)));
    return sort.dir === "desc" ? sorted.reverse() : sorted;
  }, [items, sort]);
  const toggle = (by: "date" | "amount") => setSort((cur) => (cur.by === by ? { by, dir: cur.dir === "asc" ? "desc" : "asc" } : { by, dir: by === "date" ? "desc" : "desc" }));
  const ariaSort = (by: "date" | "amount") => (sort.by === by ? (sort.dir === "asc" ? "ascending" : "descending") : "none") as "ascending" | "descending" | "none";

  async function loadMore() {
    if (!cursor) return;
    try {
      const page = await finance.get<TransactionPage>(query(cursor));
      setItems((prev) => [...prev, ...page.items]);
      setCursor(page.next_cursor);
    } catch (e) {
      setLoadError(describeError(e));
    }
  }

  function refresh() {
    setLoading(true);
    setTick((n) => n + 1);
    onChanged();
  }

  // ---- create ----
  const intent = useIntentKey();
  const [kind, setKind] = useState<Kind>("EXPENSE");
  const [accountId, setAccountId] = useState("");
  const [toAccountId, setToAccountId] = useState("");
  const [amount, setAmount] = useState("");
  const [toAmount, setToAmount] = useState("");
  const [date, setDate] = useState(todayLocal());
  const [categoryId, setCategoryId] = useState("");
  const [note, setNote] = useState("");
  const [tags, setTags] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const source = active.find((a) => a.id === (accountId || active[0]?.id));
  const target = active.find((a) => a.id === toAccountId);
  const crossCurrency = kind === "TRANSFER" && source && target && source.currency !== target.currency;
  const kindCategories = categories.filter((c) => !c.archived && c.kind === kind);

  async function create(event: FormEvent) {
    event.preventDefault();
    if (!source) return setFormError("Add an account first.");
    const problem = validateAmountText(amount, source.currency);
    if (problem) return setFormError(problem);
    if (!date) return setFormError("Choose a date.");
    setBusy(true);
    setFormError(null);
    try {
      if (kind === "TRANSFER") {
        if (!target) throw new Error("choose");
        const body: Record<string, unknown> = { from_account_id: source.id, to_account_id: target.id, amount: amount.trim(), occurred_on: date, note: note.trim() || undefined };
        if (crossCurrency || toAmount.trim()) {
          const p2 = validateAmountText(toAmount, target.currency);
          if (p2) {
            setBusy(false);
            return setFormError(`Amount received: ${p2}`);
          }
          body.to_amount = toAmount.trim();
        }
        await finance.send("POST", "/transfers", body, intent.key());
      } else {
        await finance.send("POST", "/transactions", {
          kind, account_id: source.id, amount: amount.trim(), occurred_on: date, category_id: categoryId || undefined, note: note.trim() || undefined,
          tags: tags.split(",").map((t) => t.trim()).filter(Boolean),
        }, intent.key());
      }
      intent.done();
      setAmount("");
      setToAmount("");
      setNote("");
      setTags("");
      refresh();
    } catch (e) {
      setFormError(e instanceof Error && e.message === "choose" ? "Choose the account to move money to." : describeError(e));
    } finally {
      setBusy(false);
    }
  }

  async function act(t: Transaction, action: "void" | "restore" | "confirm", reason?: string) {
    setFormError(null);
    try {
      await finance.send("POST", `/transactions/${t.id}/${action}`, action === "void" ? { reason: reason || "Voided from the app" } : {});
      refresh();
    } catch (e) {
      if (action === "void") throw new Error(describeError(e));
      setFormError(describeError(e));
    }
  }

  // ---- edit ----
  const [editing, setEditing] = useState<Transaction | null>(null);
  const [editAmount, setEditAmount] = useState("");
  const [editDate, setEditDate] = useState("");
  const [editNote, setEditNote] = useState("");
  const [editCategory, setEditCategory] = useState("");

  function startEdit(t: Transaction) {
    setEditing(t);
    setEditAmount(t.amount);
    setEditDate(t.occurred_on);
    setEditNote(t.note ?? "");
    setEditCategory(t.category_id ?? "");
    setFormError(null);
  }

  async function saveEdit(event: FormEvent) {
    event.preventDefault();
    if (!editing) return;
    const problem = validateAmountText(editAmount, editing.currency);
    if (problem) return setFormError(problem);
    try {
      const body: Record<string, unknown> = { amount: editAmount.trim(), occurred_on: editDate, note: editNote, version: editing.version };
      if (editing.kind === "INCOME" || editing.kind === "EXPENSE") {
        if (editCategory) body.category_id = editCategory;
        else body.uncategorized = true;
      }
      await finance.send("PATCH", `/transactions/${editing.id}`, body);
      setEditing(null);
      refresh();
    } catch (e) {
      setFormError(describeError(e));
    }
  }

  const accountName = (id: string | null) => accounts.find((a) => a.id === id)?.name ?? "Unknown account";
  const categoryName = (id: string | null) => categories.find((c) => c.id === id)?.name;

  return (
    <>
      <form onSubmit={create} aria-labelledby="tx-title" noValidate className="panel" style={{ maxWidth: "34rem" }}>
        <h2 id="tx-title">Add a transaction</h2>
        <label htmlFor="tx-kind">Type</label>
        <select id="tx-kind" value={kind} onChange={(e) => { setKind(e.target.value as Kind); setCategoryId(""); }} disabled={busy}>
          <option value="EXPENSE">Expense</option>
          <option value="INCOME">Income</option>
          <option value="TRANSFER">Transfer between my accounts</option>
        </select>
        <label htmlFor="tx-account">{kind === "TRANSFER" ? "From account" : "Account"}</label>
        <select id="tx-account" value={source?.id ?? ""} onChange={(e) => setAccountId(e.target.value)} disabled={busy}>
          {active.map((a) => <option key={a.id} value={a.id}>{a.name} ({a.currency})</option>)}
        </select>
        {kind === "TRANSFER" && (
          <>
            <label htmlFor="tx-to">To account</label>
            <select id="tx-to" value={toAccountId} onChange={(e) => setToAccountId(e.target.value)} disabled={busy}>
              <option value="">Choose…</option>
              {active.filter((a) => a.id !== source?.id).map((a) => <option key={a.id} value={a.id}>{a.name} ({a.currency})</option>)}
            </select>
          </>
        )}
        <label htmlFor="tx-amount">Amount{source ? ` (${source.currency})` : ""}</label>
        <input id="tx-amount" value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" disabled={busy} />
        {kind === "TRANSFER" && target && (
          <>
            <label htmlFor="tx-to-amount">Amount received ({target.currency}){crossCurrency ? "" : " — leave empty unless there was a fee"}</label>
            <input id="tx-to-amount" value={toAmount} onChange={(e) => setToAmount(e.target.value)} inputMode="decimal" disabled={busy} />
          </>
        )}
        <label htmlFor="tx-date">Date</label>
        <input id="tx-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} disabled={busy} />
        {kind !== "TRANSFER" && (
          <>
            <label htmlFor="tx-category">Category</label>
            <select id="tx-category" value={categoryId} onChange={(e) => setCategoryId(e.target.value)} disabled={busy}>
              <option value="">Uncategorised</option>
              {kindCategories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
            <label htmlFor="tx-tags">Tags (comma separated)</label>
            <input id="tx-tags" value={tags} onChange={(e) => setTags(e.target.value)} disabled={busy} />
          </>
        )}
        <label htmlFor="tx-note">Note</label>
        <input id="tx-note" value={note} onChange={(e) => setNote(e.target.value)} disabled={busy} maxLength={1000} />
        {formError && <Notice>{formError}</Notice>}
        <button type="submit" disabled={busy || active.length === 0}>{busy ? "Saving…" : "Save transaction"}</button>
        {active.length === 0 && <p>Add an account on the Accounts tab before recording transactions.</p>}
      </form>

      <section aria-labelledby="tx-list-title">
        <h2 id="tx-list-title">Transactions</h2>
        <div className="filters" role="search" aria-label="Filter transactions">
          <div className="column"><label htmlFor="f-from">From</label><input id="f-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
          <div className="column"><label htmlFor="f-to">To</label><input id="f-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
          <div className="column">
            <label htmlFor="f-account">Account</label>
            <select id="f-account" value={accountFilter} onChange={(e) => setAccountFilter(e.target.value)}>
              <option value="">All accounts</option>
              {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
          </div>
          <div className="column">
            <label htmlFor="f-kind">Type</label>
            <select id="f-kind" value={kindFilter} onChange={(e) => setKindFilter(e.target.value)}>
              <option value="">All types</option>
              <option value="EXPENSE">Expense</option>
              <option value="INCOME">Income</option>
              <option value="TRANSFER">Transfer</option>
              <option value="REFUND">Refund</option>
              <option value="ADJUSTMENT">Adjustment</option>
            </select>
          </div>
          <div className="column"><label htmlFor="f-search">Search notes</label><input id="f-search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search…" /></div>
          <label><input type="checkbox" checked={showVoided} onChange={(e) => setShowVoided(e.target.checked)} /> Show voided</label>
        </div>
        {loading && <Loading label="Loading transactions…" />}
        {loadError && (
          <>
            <Notice>{loadError}</Notice>
            <button type="button" onClick={refresh}>Try again</button>
          </>
        )}
        {!loading && !loadError && items.length === 0 && <p>No transactions match. Add one above, or widen the filters.</p>}
        {items.length > 0 && (
          <table>
            <caption className="visually-hidden">Transactions, newest first</caption>
            <thead>
              <tr>
                <th scope="col" aria-sort={ariaSort("date")}><button type="button" className="link" onClick={() => toggle("date")} style={{ textDecoration: "none", textTransform: "inherit" }}>Date {sort.by === "date" ? (sort.dir === "asc" ? "▲" : "▼") : ""}</button></th>
                <th scope="col">Details</th>
                <th scope="col" aria-sort={ariaSort("amount")}><button type="button" className="link" onClick={() => toggle("amount")} style={{ textDecoration: "none", textTransform: "inherit" }}>Amount {sort.by === "amount" ? (sort.dir === "asc" ? "▲" : "▼") : ""}</button></th>
                <th scope="col">Status</th><th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((t) => (
                <tr key={t.id} className={t.status === "VOIDED" ? "voided" : undefined}>
                  <td>{t.occurred_on}</td>
                  <td>
                    {t.payee && <strong style={{ display: "block" }}>{t.payee}</strong>}
                    {t.kind === "TRANSFER" ? `Transfer: ${accountName(t.account_id)} → ${accountName(t.to_account_id)}` : `${accountName(t.account_id)}${categoryName(t.category_id) ? ` · ${categoryName(t.category_id)}` : ""}`}
                    {t.note && <div className="muted">{t.note}</div>}
                    {t.tags.length > 0 && <div className="muted">#{t.tags.join(" #")}</div>}
                  </td>
                  <td>
                    {t.kind === "EXPENSE" ? "−" : t.kind === "INCOME" || t.kind === "REFUND" ? "+" : t.kind === "ADJUSTMENT" ? (t.direction === "DECREASE" ? "−" : "+") : ""}
                    {formatMoney(t.amount, t.currency)}
                    {t.kind === "TRANSFER" && t.to_amount && t.to_currency && ` → ${formatMoney(t.to_amount, t.to_currency)}`}
                  </td>
                  <td>{t.kind === "TRANSFER" ? "Transfer" : t.kind === "ADJUSTMENT" ? "Adjustment" : t.kind === "REFUND" ? "Refund" : ""} {t.status === "DRAFT" ? "Draft" : t.status === "VOIDED" ? "Voided" : ""}</td>
                  <td>
                    {t.status === "DRAFT" && <button type="button" className="link" onClick={() => void act(t, "confirm")}>Confirm</button>}
                    {t.status !== "VOIDED" && t.kind !== "ADJUSTMENT" && <button type="button" className="link" onClick={() => startEdit(t)}>Edit</button>}
                    {t.status !== "VOIDED" && <button type="button" className="link" onClick={() => setVoiding(t)}>Void</button>}
                    {t.status === "VOIDED" && <button type="button" className="link" onClick={() => void act(t, "restore")}>Restore</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {items.length > 0 && <p className="muted">Showing {items.length} transaction{items.length === 1 ? "" : "s"}{cursor ? " so far" : ""}. Sorting applies to the rows loaded; the server lists newest first.</p>}
        {cursor && <button type="button" onClick={() => void loadMore()}>Load more</button>}
        <ReasonDialog open={voiding !== null} title="Void this transaction" description="A voided transaction stays in the history but no longer counts towards balances, budgets or reports. You can restore it later." label="Reason" confirmLabel="Void transaction" danger
          onConfirm={(reason) => act(voiding!, "void", reason)} onClose={() => setVoiding(null)} />
        {editing && (
          <form onSubmit={saveEdit} aria-label="Edit transaction" className="panel" noValidate>
            <h3>Edit transaction</h3>
            <label htmlFor="ed-amount">Amount ({editing.currency})</label>
            <input id="ed-amount" value={editAmount} onChange={(e) => setEditAmount(e.target.value)} inputMode="decimal" />
            <label htmlFor="ed-date">Date</label>
            <input id="ed-date" type="date" value={editDate} onChange={(e) => setEditDate(e.target.value)} />
            {(editing.kind === "INCOME" || editing.kind === "EXPENSE") && (
              <>
                <label htmlFor="ed-category">Category</label>
                <select id="ed-category" value={editCategory} onChange={(e) => setEditCategory(e.target.value)}>
                  <option value="">Uncategorised</option>
                  {categories.filter((c) => !c.archived && c.kind === editing.kind).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select>
              </>
            )}
            <label htmlFor="ed-note">Note</label>
            <input id="ed-note" value={editNote} onChange={(e) => setEditNote(e.target.value)} />
            <div className="row">
              <button type="submit">Save changes</button>
              <button type="button" className="link" onClick={() => setEditing(null)}>Cancel</button>
            </div>
          </form>
        )}
      </section>
    </>
  );
}
