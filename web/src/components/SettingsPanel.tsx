"use client";

import { useState, type FormEvent } from "react";
import { api, describeError } from "@/lib/api";
import { canManageMembers, canTransferOwnership } from "@/lib/permissions";
import { useResource } from "@/lib/useResource";
import { validateWorkspaceName } from "@/lib/validation";
import type { Member, Workspace } from "@/lib/types";
import { Notice } from "./Notice";
import { useStepUp } from "./useStepUp";

export function SettingsPanel({ workspace, onRenamed, onLeft }: { workspace: Workspace; onRenamed: () => void; onLeft: () => void }) {
  const { run, form } = useStepUp();
  const [name, setName] = useState(workspace.name);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [confirmLeave, setConfirmLeave] = useState(false);
  const [target, setTarget] = useState("");
  const [confirmTransfer, setConfirmTransfer] = useState(false);
  const owner = canTransferOwnership(workspace.role);
  const members = useResource((signal) => (owner ? api<{ items: Member[] }>(`/v1/spaces/${workspace.id}/members`, { signal }) : Promise.resolve({ items: [] as Member[] })));

  async function rename(event: FormEvent) {
    event.preventDefault();
    const problem = validateWorkspaceName(name);
    if (problem) return setError(problem);
    setError(null);
    setSaved(false);
    try {
      await api(`/v1/spaces/${workspace.id}`, { method: "PATCH", body: { name: name.trim() } });
      setSaved(true);
      onRenamed();
    } catch (e) {
      setError(describeError(e));
    }
  }

  async function leave() {
    setError(null);
    try {
      await api<void>(`/v1/spaces/${workspace.id}/members/${workspace.membership_id}`, { method: "DELETE" });
      onLeft();
    } catch (e) {
      setError(describeError(e));
      setConfirmLeave(false);
    }
  }

  async function transfer() {
    setError(null);
    try {
      const done = await run(() => api(`/v1/spaces/${workspace.id}/ownership-transfers`, { method: "POST", body: { to_membership_id: target } }));
      if (done !== undefined) onRenamed();
    } catch (e) {
      setError(describeError(e));
    } finally {
      setConfirmTransfer(false);
    }
  }

  const candidates = members.status === "ready" ? members.data.items.filter((m) => m.membership_id !== workspace.membership_id) : [];

  return (
    <>
      {error && <Notice>{error}</Notice>}
      {form}
      {canManageMembers(workspace.role) && (
        <form onSubmit={rename} aria-labelledby="rename-title" noValidate>
          <h2 id="rename-title">Workspace name</h2>
          <label htmlFor="ws-rename">Name</label>
          <input id="ws-rename" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} />
          {saved && <Notice kind="success">Saved.</Notice>}
          <button type="submit">Save name</button>
        </form>
      )}
      {owner && (
        <section aria-labelledby="transfer-title">
          <h2 id="transfer-title">Transfer ownership</h2>
          <p>The new Owner must have two-step verification enabled. You become an Admin.</p>
          <label htmlFor="transfer-target">New Owner</label>
          <select id="transfer-target" value={target} onChange={(e) => { setTarget(e.target.value); setConfirmTransfer(false); }}>
            <option value="">Choose a member…</option>
            {candidates.map((m) => <option key={m.membership_id} value={m.membership_id}>{m.display_name} ({m.email})</option>)}
          </select>
          {confirmTransfer ? (
            <button type="button" onClick={() => void transfer()}>Confirm transfer</button>
          ) : (
            <button type="button" disabled={!target} onClick={() => setConfirmTransfer(true)}>Transfer ownership…</button>
          )}
        </section>
      )}
      <section aria-labelledby="leave-title">
        <h2 id="leave-title">Leave this workspace</h2>
        <p>You lose access immediately. A workspace must keep at least one Owner.</p>
        {confirmLeave ? (
          <>
            <button type="button" onClick={() => void leave()}>Yes, leave</button>
            <button type="button" className="link" onClick={() => setConfirmLeave(false)}>Cancel</button>
          </>
        ) : (
          <button type="button" onClick={() => setConfirmLeave(true)}>Leave workspace</button>
        )}
      </section>
    </>
  );
}
