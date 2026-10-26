"use client";

import { useState, type FormEvent } from "react";
import { api, describeError } from "@/lib/api";
import { Notice } from "./Notice";

/** Re-authentication for sensitive actions (role changes, ownership transfer). Valid for a few minutes on the server. */
export function StepUpForm({ mfaEnabled, onDone, onCancel }: { mfaEnabled: boolean; onDone: () => void; onCancel: () => void }) {
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!password || (mfaEnabled && !code.trim())) {
      setError(mfaEnabled ? "Enter your password and a current code." : "Enter your password.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api("/v1/auth/step-up", { method: "POST", body: { password, code: code.trim() || undefined } });
      onDone();
    } catch (e) {
      setError(describeError(e));
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} aria-label="Confirm your identity" className="card" noValidate>
      <h3>Confirm it is you</h3>
      <label htmlFor="stepup-password">Password</label>
      <input id="stepup-password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} disabled={busy} />
      {mfaEnabled && (
        <>
          <label htmlFor="stepup-code">Authenticator code</label>
          <input id="stepup-code" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} disabled={busy} />
        </>
      )}
      {error && <Notice>{error}</Notice>}
      <div className="row">
        <button type="submit" disabled={busy}>
          {busy ? "Confirming…" : "Confirm"}
        </button>
        <button type="button" className="link" onClick={onCancel} disabled={busy}>
          Cancel
        </button>
      </div>
    </form>
  );
}
