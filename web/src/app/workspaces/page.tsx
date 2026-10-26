"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { AuthGate } from "@/components/AuthGate";
import { Loading, Notice } from "@/components/Notice";
import { api, describeError } from "@/lib/api";
import { setActiveWorkspaceId } from "@/lib/activeWorkspace";
import type { Workspace } from "@/lib/types";
import { roleLabel } from "@/lib/permissions";
import { useResource } from "@/lib/useResource";
import { validateWorkspaceName } from "@/lib/validation";

function WorkspaceList() {
  const router = useRouter();
  const list = useResource((signal) => api<{ items: Workspace[] }>("/v1/workspaces", { signal }));
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function create(event: FormEvent) {
    event.preventDefault();
    const problem = validateWorkspaceName(name);
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const created = await api<Workspace>("/v1/workspaces", { method: "POST", body: { name: name.trim() } });
      setActiveWorkspaceId(created.id);
      router.push(`/workspaces/${created.id}`);
    } catch (e) {
      setError(describeError(e));
      setBusy(false);
    }
  }

  return (
    <main>
      <h1>Your workspaces</h1>
      {list.status === "loading" && <Loading label="Loading workspaces…" />}
      {list.status === "error" && (
        <>
          <Notice>{describeError(list.error)}</Notice>
          <button type="button" onClick={list.reload}>Try again</button>
        </>
      )}
      {list.status === "ready" && (
        <ul className="cards" aria-label="Workspaces">
          {list.data.items.map((w) => (
            <li key={w.id} className="card">
              <Link href={`/workspaces/${w.id}`} onClick={() => setActiveWorkspaceId(w.id)}>
                {w.type === "PERSONAL" ? "Personal profile" : w.name}
              </Link>
              <span className="badge">{w.type === "PERSONAL" ? "Private to you" : roleLabel(w.role)}</span>
            </li>
          ))}
        </ul>
      )}
      <form onSubmit={create} aria-labelledby="create-title" noValidate>
        <h2 id="create-title">Create a business workspace</h2>
        <label htmlFor="ws-name">Workspace name</label>
        <input id="ws-name" value={name} onChange={(e) => setName(e.target.value)} disabled={busy} maxLength={120} />
        {error && <Notice>{error}</Notice>}
        <button type="submit" disabled={busy}>{busy ? "Creating…" : "Create workspace"}</button>
      </form>
    </main>
  );
}

export default function WorkspacesPage() {
  return (
    <AuthGate>
      <WorkspaceList />
    </AuthGate>
  );
}
