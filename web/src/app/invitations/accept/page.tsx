"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { AuthGate } from "@/components/AuthGate";
import { Notice } from "@/components/Notice";
import { api, describeError } from "@/lib/api";
import { setActiveWorkspaceId } from "@/lib/activeWorkspace";
import type { Workspace } from "@/lib/types";

function Accept() {
  // Read once from the fragment (pure read; this component only mounts in the browser after sign-in).
  const [token, setToken] = useState<string | null>(() => {
    const match = /(?:^#|&)token=([^&]+)/.exec(window.location.hash);
    return match?.[1] ? decodeURIComponent(match[1]) : null;
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [joined, setJoined] = useState<Workspace | null>(null);
  const [declined, setDeclined] = useState(false);

  // The token travels in the URL fragment, which browsers never send to servers or write to logs. Remove it from the
  // address bar as soon as it is in memory.
  useEffect(() => {
    if (window.location.hash) window.history.replaceState(null, "", window.location.pathname);
  }, []);

  async function respond(action: "accept" | "decline") {
    setBusy(true);
    setError(null);
    try {
      if (action === "accept") {
        const ws = await api<Workspace>("/v1/invitations/accept", { method: "POST", body: { token } });
        setActiveWorkspaceId(ws.id);
        setJoined(ws);
      } else {
        await api<void>("/v1/invitations/decline", { method: "POST", body: { token } });
        setDeclined(true);
      }
      setToken(null);
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  if (joined) {
    return (
      <main>
        <h1>You have joined {joined.name}</h1>
        <p><Link href={`/workspaces/${joined.id}`}>Open the workspace</Link></p>
      </main>
    );
  }
  if (declined) {
    return <main><h1>Invitation declined</h1><p><Link href="/workspaces">Back to your workspaces</Link></p></main>;
  }
  if (!token) {
    return <main><h1>Invitation</h1><Notice>This invitation link is missing or has already been used. Ask the workspace Owner for a new one.</Notice></main>;
  }
  return (
    <main>
      <h1>Join a workspace</h1>
      <p>You have been invited to a business workspace. Accept only if you recognise the sender; the invitation must be addressed to the email you signed in with.</p>
      {error && <Notice>{error}</Notice>}
      <div className="row">
        <button type="button" disabled={busy} onClick={() => void respond("accept")}>{busy ? "Working…" : "Accept invitation"}</button>
        <button type="button" className="link" disabled={busy} onClick={() => void respond("decline")}>Decline</button>
      </div>
    </main>
  );
}

export default function AcceptInvitationPage() {
  return (
    <AuthGate>
      <Accept />
    </AuthGate>
  );
}
