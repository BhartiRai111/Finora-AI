"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import type { Category } from "@/lib/finance";
import { useFinance, useIntentKey } from "@/lib/FinanceContext";
import { Notice } from "../Notice";

export function CategoriesPanel({ categories, onChanged }: { categories: Category[]; onChanged: () => void }) {
  const finance = useFinance();
  const intent = useIntentKey();
  const [name, setName] = useState("");
  const [kind, setKind] = useState("EXPENSE");
  const [parent, setParent] = useState("");
  const [mergeFrom, setMergeFrom] = useState<string | null>(null);
  const [mergeInto, setMergeInto] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function run(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      onChanged();
    } catch (e) {
      setError(describeError(e));
    }
  }

  function create(event: FormEvent) {
    event.preventDefault();
    if (!name.trim()) return setError("Enter a category name.");
    void run(async () => {
      await finance.send("POST", "/categories", { name: name.trim(), kind, parent_id: parent || undefined }, intent.key());
      intent.done();
      setName("");
    });
  }

  const live = categories.filter((c) => !c.archived);
  const tops = live.filter((c) => !c.parent_id && c.kind === kind);
  return (
    <>
      {error && <Notice>{error}</Notice>}
      {categories.length === 0 && (
        <p>
          No categories yet.{" "}
          <button type="button" onClick={() => void run(() => finance.send("POST", "/categories/starter", {}))}>Add a starter set</button>
        </p>
      )}
      <ul className="cards" aria-label="Categories">
        {categories.map((c) => (
          <li key={c.id} className={c.archived ? "card voided" : "card"}>
            <div className="column">
              <strong>{c.parent_id ? "↳ " : ""}{c.name}</strong>
              <span className="muted">{c.kind === "INCOME" ? "Income" : "Expense"}{c.archived && " · archived"}</span>
              {mergeFrom === c.id && (
                <div className="row">
                  <label htmlFor={`merge-${c.id}`}>Merge into</label>
                  <select id={`merge-${c.id}`} value={mergeInto} onChange={(e) => setMergeInto(e.target.value)}>
                    <option value="">Choose a category</option>
                    {live.filter((o) => o.id !== c.id && o.kind === c.kind).map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                  </select>
                  <button type="button" disabled={!mergeInto} onClick={() => void run(async () => { await finance.send("POST", `/categories/${c.id}/merge`, { into_category_id: mergeInto }); setMergeFrom(null); })}>Merge</button>
                </div>
              )}
            </div>
            <div className="row">
              {!c.archived && <button type="button" className="link" onClick={() => { setMergeFrom(c.id); setMergeInto(""); }}>Merge</button>}
              <button type="button" className="link" onClick={() => void run(() => finance.send("POST", `/categories/${c.id}/${c.archived ? "restore" : "archive"}`, {}))}>
                {c.archived ? "Restore" : "Archive"}
              </button>
            </div>
          </li>
        ))}
      </ul>
      <form onSubmit={create} aria-labelledby="cat-title" noValidate>
        <h2 id="cat-title">Add category</h2>
        <label htmlFor="cat-name">Name</label>
        <input id="cat-name" value={name} onChange={(e) => setName(e.target.value)} />
        <label htmlFor="cat-kind">Type</label>
        <select id="cat-kind" value={kind} onChange={(e) => { setKind(e.target.value); setParent(""); }}>
          <option value="EXPENSE">Expense</option>
          <option value="INCOME">Income</option>
        </select>
        <label htmlFor="cat-parent">Parent (optional)</label>
        <select id="cat-parent" value={parent} onChange={(e) => setParent(e.target.value)}>
          <option value="">None</option>
          {tops.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        <button type="submit">Add category</button>
      </form>
    </>
  );
}
