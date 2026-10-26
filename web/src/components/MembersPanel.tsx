"use client";

import Link from "next/link";
import { useState } from "react";
import { api, ApiError, describeError } from "@/lib/api";
import { canManageMembers, canManageTarget, grantableRoles, roleLabel } from "@/lib/permissions";
import { useResource } from "@/lib/useResource";
import type { Member, Role, Workspace } from "@/lib/types";
import { Loading, Notice } from "./Notice";
import { useStepUp } from "./useStepUp";

export function MembersPanel({ workspace }: { workspace: Workspace }) {
  const members = useResource((signal) => api<{ items: Member[] }>(`/v1/spaces/${workspace.id}/members`, { signal }));
  const { run, form } = useStepUp();
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const mayManage = canManageMembers(workspace.role);

  async function act(membershipId: string, action: () => Promise<unknown>) {
    setBusy(membershipId);
    setError(null);
    try {
      await run(action);
      members.reload();
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(null);
      setConfirming(null);
    }
  }

  if (members.status === "loading") return <Loading label="Loading members…" />;
  if (members.status === "error") {
    const mfa = members.error instanceof ApiError && members.error.code === "MFA_REQUIRED";
    return (
      <>
        <Notice>{describeError(members.error)}</Notice>
        {mfa && <p>Set up two-step verification in your <Link href="/account">account</Link> to manage this workspace.</p>}
        <button type="button" onClick={members.reload}>Try again</button>
      </>
    );
  }

  return (
    <>
      {error && <Notice>{error}</Notice>}
      {form}
      <table>
        <caption className="visually-hidden">Workspace members</caption>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Email</th><th scope="col">Role</th>{mayManage && <th scope="col">Actions</th>}</tr>
        </thead>
        <tbody>
          {members.data.items.map((m) => {
            const self = m.membership_id === workspace.membership_id;
            const manageable = mayManage && !self && canManageTarget(workspace.role, m.role);
            return (
              <tr key={m.membership_id}>
                <td>{m.display_name}{self && " (you)"}</td>
                <td>{m.email}</td>
                <td>
                  {manageable ? (
                    <select
                      aria-label={`Role for ${m.display_name}`}
                      value={m.role}
                      disabled={busy === m.membership_id}
                      onChange={(e) => void act(m.membership_id, () => api(`/v1/spaces/${workspace.id}/members/${m.membership_id}`, { method: "PATCH", body: { role: e.target.value as Role } }))}
                    >
                      {grantableRoles(workspace.role).map((r) => <option key={r} value={r}>{roleLabel(r)}</option>)}
                    </select>
                  ) : roleLabel(m.role)}
                </td>
                {mayManage && (
                  <td>
                    {manageable && (confirming === m.membership_id ? (
                      <>
                        <button type="button" disabled={busy === m.membership_id} onClick={() => void act(m.membership_id, () => api(`/v1/spaces/${workspace.id}/members/${m.membership_id}`, { method: "DELETE" }))}>
                          Confirm removal
                        </button>
                        <button type="button" className="link" onClick={() => setConfirming(null)}>Cancel</button>
                      </>
                    ) : (
                      <button type="button" className="link" onClick={() => setConfirming(m.membership_id)}>Remove</button>
                    ))}
                  </td>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </>
  );
}
