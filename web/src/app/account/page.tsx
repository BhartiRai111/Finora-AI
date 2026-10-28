"use client";

import { useState, type FormEvent } from "react";
import { AuthGate } from "@/components/AuthGate";
import { Notice } from "@/components/Notice";
import { api, describeError, setCsrfToken } from "@/lib/api";
import { useSession } from "@/lib/session";

function Profile() {
  const { state, refresh } = useSession();
  const user = state.status === "ready" ? state.user : null;
  const [name, setName] = useState(user?.display_name ?? "");
  const [msg, setMsg] = useState<{ kind: "success" | "error"; text: string } | null>(null);

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!name.trim()) return setMsg({ kind: "error", text: "Enter your name." });
    try {
      await api("/v1/me", { method: "PATCH", body: { display_name: name.trim() } });
      await refresh();
      setMsg({ kind: "success", text: "Saved." });
    } catch (e) {
      setMsg({ kind: "error", text: describeError(e) });
    }
  }

  return (
    <form onSubmit={save} aria-labelledby="profile-title" noValidate>
      <h2 id="profile-title">Profile</h2>
      <p>{user?.email}</p>
      <label htmlFor="profile-name">Your name</label>
      <input id="profile-name" value={name} onChange={(e) => setName(e.target.value)} />
      {msg && <Notice kind={msg.kind}>{msg.text}</Notice>}
      <button type="submit">Save</button>
    </form>
  );
}

function Mfa() {
  const { state, refresh } = useSession();
  const enabled = state.status === "ready" && state.user.mfa_enabled === true;
  const [secret, setSecret] = useState<{ secret: string; otpauth_uri: string } | null>(null);
  const [code, setCode] = useState("");
  const [codes, setCodes] = useState<string[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function start() {
    setBusy(true);
    setError(null);
    try {
      setSecret(await api("/v1/auth/mfa/enroll", { method: "POST" }));
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  async function confirm(event: FormEvent) {
    event.preventDefault();
    if (!/^\d{6}$/.test(code.trim())) return setError("Enter the 6-digit code from your authenticator app.");
    setBusy(true);
    setError(null);
    try {
      const result = await api<{ csrf_token: string; recovery_codes: string[] }>("/v1/auth/mfa/confirm", { method: "POST", body: { code: code.trim() } });
      setCsrfToken(result.csrf_token);
      setCodes(result.recovery_codes);
      setSecret(null);
      setCode("");
      await refresh();
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="mfa-title">
      <h2 id="mfa-title">Two-step verification</h2>
      {codes ? (
        <>
          <Notice kind="success">Two-step verification is on. Save these recovery codes now — they are shown only once and each works one time.</Notice>
          <ul className="codes" aria-label="Recovery codes">{codes.map((c) => <li key={c}><code>{c}</code></li>)}</ul>
          <button type="button" onClick={() => setCodes(null)}>I have saved them</button>
        </>
      ) : enabled ? (
        <p>Enabled. Owners and Admins of a business workspace must keep it on.</p>
      ) : secret ? (
        <form onSubmit={confirm} noValidate>
          <p>Add this key to an authenticator app (or open the link on your phone), then enter the 6-digit code it shows.</p>
          <p><code aria-label="Setup key">{secret.secret}</code></p>
          <p><a href={secret.otpauth_uri}>Open in authenticator app</a></p>
          <label htmlFor="mfa-confirm">6-digit code</label>
          <input id="mfa-confirm" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} disabled={busy} />
          {error && <Notice>{error}</Notice>}
          <button type="submit" disabled={busy}>{busy ? "Checking…" : "Turn on"}</button>
        </form>
      ) : (
        <>
          <p>Add a second step to sign-in. Required for Owners and Admins of business workspaces.</p>
          {error && <Notice>{error}</Notice>}
          <button type="button" onClick={() => void start()} disabled={busy}>{busy ? "Starting…" : "Set up"}</button>
        </>
      )}
    </section>
  );
}

function PasswordChange() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [msg, setMsg] = useState<{ kind: "success" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!current || next.length < 12) return setMsg({ kind: "error", text: "Enter your current password and a new one of at least 12 characters." });
    setBusy(true);
    try {
      const result = await api<{ csrf_token: string }>("/v1/auth/password/change", { method: "POST", body: { current_password: current, new_password: next } });
      setCsrfToken(result.csrf_token);
      setCurrent("");
      setNext("");
      setMsg({ kind: "success", text: "Password changed. You were signed out of your other devices." });
    } catch (e) {
      setMsg({ kind: "error", text: describeError(e) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} aria-labelledby="pw-title" noValidate>
      <h2 id="pw-title">Change password</h2>
      <label htmlFor="pw-current">Current password</label>
      <input id="pw-current" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} disabled={busy} />
      <label htmlFor="pw-new">New password</label>
      <input id="pw-new" type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} disabled={busy} />
      {msg && <Notice kind={msg.kind}>{msg.text}</Notice>}
      <button type="submit" disabled={busy}>{busy ? "Changing…" : "Change password"}</button>
    </form>
  );
}

export default function AccountPage() {
  return (
    <AuthGate>
      <main id="main" className="page-narrow">
        <h1>Your account</h1>
        <Profile />
        <Mfa />
        <PasswordChange />
      </main>
    </AuthGate>
  );
}
