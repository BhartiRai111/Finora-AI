"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { api, ApiError, describeError } from "@/lib/api";
import { canManageMembers, grantableRoles, roleLabel } from "@/lib/permissions";
import { useResource } from "@/lib/useResource";
import { validateInvite } from "@/lib/validation";
import type { Invitation, Role, Workspace } from "@/lib/types";
import { Loading, Notice } from "./Notice";

export function deliveryMessage(delivery: Invitation["delivery"]): { kind: "success" | "info" | "error"; text: string } {
  switch (delivery) {
    case "SENT":
      return { kind: "success", text: "Invitation created and handed to the email provider." };
    case "DEV_CAPTURED":
      return { kind: "info", text: "Invitation created. Development mode: the link was saved to a local file and NO email was sent." };
    case "FAILED":
      return { kind: "error", text: "Invitation created, but delivery failed. Revoke it and try again later." };
    default:
      return { kind: "info", text: "Invitation created, but email delivery is not configured on this server. NO email was sent." };
  }
}

export function InvitationsPanel({ workspace }: { workspace: Workspace }) {
  const list = useResource((signal) => api<{ items: Invitation[] }>(`/v1/spaces/${workspace.id}/invitations`, { signal }));
  const roles = grantableRoles(workspace.role);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<Role>(roles.includes("VIEWER") ? "VIEWER" : (roles[0] ?? "VIEWER"));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<{ kind: "success" | "info" | "error"; text: string } | null>(null);

  async function invite(event: FormEvent) {
    event.preventDefault();
    const problem = validateInvite({ email });
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const created = await api<Invitation>(`/v1/spaces/${workspace.id}/invitations`, { method: "POST", body: { email: email.trim(), role } });
      setResult(deliveryMessage(created.delivery));
      setEmail("");
      list.reload();
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  async function revoke(id: string) {
    setError(null);
    try {
      await api<void>(`/v1/spaces/${workspace.id}/invitations/${id}`, { method: "DELETE" });
      list.reload();
    } catch (e) {
      setError(describeError(e));
    }
  }

  if (list.status === "loading") return <Loading label="Loading invitations…" />;
  if (list.status === "error") {
    const mfa = list.error instanceof ApiError && list.error.code === "MFA_REQUIRED";
    return (
      <>
        <Notice>{describeError(list.error)}</Notice>
        {mfa && <p>Set up two-step verification in your <Link href="/account">account</Link> to manage this workspace.</p>}
        <button type="button" onClick={list.reload}>Try again</button>
      </>
    );
  }

  return (
    <>
      {canManageMembers(workspace.role) && (
        <form onSubmit={invite} aria-labelledby="invite-title" noValidate>
          <h2 id="invite-title">Invite someone</h2>
          <label htmlFor="invite-email">Email</label>
          <input id="invite-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} disabled={busy} />
          <label htmlFor="invite-role">Role</label>
          <select id="invite-role" value={role} onChange={(e) => setRole(e.target.value as Role)} disabled={busy}>
            {roles.map((r) => <option key={r} value={r}>{roleLabel(r)}</option>)}
          </select>
          {error && <Notice>{error}</Notice>}
          {result && <Notice kind={result.kind === "error" ? "error" : result.kind}>{result.text}</Notice>}
          <button type="submit" disabled={busy}>{busy ? "Inviting…" : "Send invitation"}</button>
        </form>
      )}
      {!canManageMembers(workspace.role) && error && <Notice>{error}</Notice>}
      {list.data.items.length === 0 ? (
        <p>No invitations yet.</p>
      ) : (
        <table>
          <caption className="visually-hidden">Invitations</caption>
          <thead><tr><th scope="col">Email</th><th scope="col">Role</th><th scope="col">Status</th><th scope="col">Expires</th>{canManageMembers(workspace.role) && <th scope="col">Actions</th>}</tr></thead>
          <tbody>
            {list.data.items.map((i) => (
              <tr key={i.id}>
                <td>{i.email}</td>
                <td>{roleLabel(i.role)}</td>
                <td>{i.status.charAt(0) + i.status.slice(1).toLowerCase()}</td>
                <td>{new Date(i.expires_at).toLocaleDateString()}</td>
                {canManageMembers(workspace.role) && (
                  <td>{i.status === "PENDING" && <button type="button" className="link" onClick={() => void revoke(i.id)}>Revoke</button>}</td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
