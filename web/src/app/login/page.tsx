"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { MfaForm } from "@/components/MfaForm";
import { Notice } from "@/components/Notice";
import { describeError } from "@/lib/api";
import { safeNext } from "@/lib/redirect";
import { useSession } from "@/lib/session";

export default function LoginPage() {
  const { state, login } = useSession();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (state.status === "ready") router.replace(safeNext(new URLSearchParams(window.location.search).get("next")) ?? "/workspaces");
  }, [state.status, router]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!email.trim() || !password) {
      setError("Enter your email and password.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await login(email.trim(), password);
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  if (state.status === "mfa") return <main id="main"><MfaForm /></main>;

  return (
    <main id="main">
      <form onSubmit={submit} aria-labelledby="login-title" noValidate>
        <h1 id="login-title">Sign in</h1>
        <label htmlFor="login-email">Email</label>
        <input id="login-email" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} disabled={busy} />
        <label htmlFor="login-password">Password</label>
        <input id="login-password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} disabled={busy} />
        {error && <Notice>{error}</Notice>}
        <button type="submit" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
        <p>
          New here? <Link href="/register">Create an account</Link>
        </p>
      </form>
    </main>
  );
}
