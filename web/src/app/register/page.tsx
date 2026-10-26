"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Notice } from "@/components/Notice";
import { api, describeError } from "@/lib/api";
import { useSession } from "@/lib/session";
import { validateRegistration } from "@/lib/validation";

export default function RegisterPage() {
  const { login } = useSession();
  const router = useRouter();
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const problem = validateRegistration({ name, email, password });
    if (problem) {
      setError(problem);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api("/v1/auth/register", { method: "POST", body: { email: email.trim(), password, display_name: name.trim() } });
      await login(email.trim(), password);
      router.push("/workspaces");
    } catch (e) {
      setError(describeError(e));
      setBusy(false);
    }
  }

  return (
    <main>
      <form onSubmit={submit} aria-labelledby="register-title" noValidate>
        <h1 id="register-title">Create your account</h1>
        <label htmlFor="reg-name">Your name</label>
        <input id="reg-name" autoComplete="name" value={name} onChange={(e) => setName(e.target.value)} disabled={busy} />
        <label htmlFor="reg-email">Email</label>
        <input id="reg-email" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} disabled={busy} />
        <label htmlFor="reg-password">Password (12 or more characters)</label>
        <input id="reg-password" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} disabled={busy} />
        {error && <Notice>{error}</Notice>}
        <button type="submit" disabled={busy}>
          {busy ? "Creating…" : "Create account"}
        </button>
        <p>
          Already registered? <Link href="/login">Sign in</Link>
        </p>
      </form>
    </main>
  );
}
