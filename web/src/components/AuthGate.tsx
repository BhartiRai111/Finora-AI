"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { useSession } from "@/lib/session";
import { MfaForm } from "./MfaForm";
import { Loading, Notice } from "./Notice";

/** Renders children only for a fully signed-in user. Every other state has an explicit, accessible screen. */
export function AuthGate({ children }: { children: ReactNode }) {
  const { state, refresh } = useSession();
  switch (state.status) {
    case "loading":
      return <Loading label="Checking your session…" />;
    case "anonymous":
      return (
        <section>
          <h1>Sign in required</h1>
          <p>
            Please <Link href="/login">sign in</Link> or <Link href="/register">create an account</Link> to continue.
          </p>
        </section>
      );
    case "mfa":
      return <MfaForm />;
    case "error":
      return (
        <section>
          <Notice>We could not check your session. The service may be unavailable.</Notice>
          <button type="button" onClick={() => void refresh()}>
            Try again
          </button>
        </section>
      );
    case "ready":
      return <>{children}</>;
  }
}
