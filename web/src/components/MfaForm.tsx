"use client";

import { useState, type FormEvent } from "react";
import { describeError } from "@/lib/api";
import { useSession } from "@/lib/session";
import { Notice } from "./Notice";

/** Second sign-in step. Until it succeeds the session can do nothing else (the server enforces that). */
export function MfaForm() {
  const { verifyMfa, logout } = useSession();
  const [useRecovery, setUseRecovery] = useState(false);
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const trimmed = value.trim();
    if (!trimmed) {
      setError(useRecovery ? "Enter a recovery code." : "Enter the 6-digit code from your authenticator app.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await verifyMfa(useRecovery ? { recovery_code: trimmed } : { code: trimmed });
    } catch (e) {
      setError(describeError(e));
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} aria-labelledby="mfa-title" noValidate>
      <h1 id="mfa-title">Two-step verification</h1>
      <p>{useRecovery ? "Enter one of your recovery codes. Each works once." : "Enter the code from your authenticator app."}</p>
      <label htmlFor="mfa-value">{useRecovery ? "Recovery code" : "6-digit code"}</label>
      <input
        id="mfa-value"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        autoComplete="one-time-code"
        inputMode={useRecovery ? "text" : "numeric"}
        disabled={busy}
      />
      {error && <Notice>{error}</Notice>}
      <div className="row">
        <button type="submit" disabled={busy}>
          {busy ? "Verifying…" : "Verify"}
        </button>
        <button type="button" className="link" onClick={() => { setUseRecovery(!useRecovery); setValue(""); setError(null); }}>
          {useRecovery ? "Use authenticator code" : "Use a recovery code"}
        </button>
        <button type="button" className="link" onClick={() => void logout()}>
          Sign out
        </button>
      </div>
    </form>
  );
}
